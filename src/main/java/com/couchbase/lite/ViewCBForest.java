package com.couchbase.lite;

import com.couchbase.lite.cbforest.Collatable;
import com.couchbase.lite.cbforest.DocEnumerator;
import com.couchbase.lite.cbforest.EmitFn;
import com.couchbase.lite.cbforest.IndexEnumerator;
import com.couchbase.lite.cbforest.KeyRange;
import com.couchbase.lite.cbforest.MapFn;
import com.couchbase.lite.cbforest.MapReduceIndex;
import com.couchbase.lite.cbforest.MapReduceIndexer;
import com.couchbase.lite.cbforest.Mappable;
import com.couchbase.lite.cbforest.OpenFlags;
import com.couchbase.lite.cbforest.Slice;
import com.couchbase.lite.cbforest.Transaction;
import com.couchbase.lite.cbforest.VectorKeyRange;
import com.couchbase.lite.cbforest.VectorMapReduceIndex;
import com.couchbase.lite.cbforest.VersionedDocument;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.storage.Cursor;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by hideki on 12/9/14.
 */
public class ViewCBForest implements View{

    public final static String TAG ="ViewCBForest";

    // pragma mark - C++ MAP/REDUCE GLUE:

    /**
     * in CBLView.m
     * class CocoaMappable : public Mappable
     */
    public static class CocoaMappable extends Mappable{

        private Map<String, Object> body = null;

        public CocoaMappable(com.couchbase.lite.cbforest.Document doc, Map<String, Object> dict) {
            super(doc);
            body = dict;
        }

        public Map<String, Object> getBody() {
            return body;
        }
    }

    /**
     *  in CBLView.m
     *  class CocoaIndexer : public MapReduceIndexer
     */
    public static class CocoaIndexer extends MapReduceIndexer {

        private com.couchbase.lite.cbforest.KeyStore sourceStore = null;

        public CocoaIndexer(VectorMapReduceIndex indexes, Transaction t) {
            super(indexes, t);
            sourceStore = indexes.get(0).sourceStore();
        }

        /**
         * virtual void addDocument(const Document& cppDoc)
         */
        public void addDocument(com.couchbase.lite.cbforest.Document cppDoc) {
            if((VersionedDocument.flagsOfDocument(cppDoc) & VersionedDocument.kDeleted) != 0){
                CocoaMappable mappable = new CocoaMappable(cppDoc, null);
                addMappable(mappable);
            }
            else{
                VersionedDocument vDoc = new VersionedDocument(sourceStore, cppDoc);
                com.couchbase.lite.cbforest.Revision node = vDoc.currentRevision();
                EnumSet<Database.TDContentOptions> options = EnumSet.noneOf(Database.TDContentOptions.class);
                options.add(Database.TDContentOptions.TDIncludeLocalSeq);
                Map<String, Object> body = ForestBridge.bodyOfNode(node, options, vDoc);
                Log.w(TAG, String.format("Mapping %s rev %s", body.get("_id"), body.get("_rev")));
                CocoaMappable mappable = new CocoaMappable(cppDoc, body);
                addMappable(mappable);
            }
        }
    }
    /**
     *  in CBLView.m
     * class MapReduceBridge : public MapFn
     */
    public static class MapReduceBridge  extends MapFn {
        private Mapper mapBlock = null;
        private String viewName = null;
        private CBLViewIndexType indexType = CBLViewIndexType.kCBLUnknown;

        public void setMapBlock(Mapper mapBlock) {
            this.mapBlock = mapBlock;
        }

        public void setViewName(String viewName) {
            this.viewName = viewName;
        }

        public void setIndexType(CBLViewIndexType indexType) {
            this.indexType = indexType;
        }

        /**
         * virtual void operator() (const Mappable& mappable, EmitFn& emitFn)
         */
        public void call(Mappable mappable, final EmitFn emitFn) {
            Log.w(TAG, "[MapReduceBridge.call()] START");
            if(!(mappable instanceof CocoaMappable)) {
                Log.e(TAG, "mappable is not a instanceof CocoaMappable");
                //return;
            }
            CocoaMappable cocoaMappable = (CocoaMappable)mappable;
            final Map<String, Object> doc = cocoaMappable.getBody();
            if(doc == null) {
                Log.w(TAG, "doc is null");
                return;
            }

            Emitter emitter = new Emitter(){
                public void emit(Object key, Object value){
                    if(indexType == CBLViewIndexType.kCBLFullTextIndex){
                        Log.e(TAG, "Full Text Index is not supported yet!!!!");
                    }
                    else if(key instanceof SpecialKey){
                        Log.e(TAG, "Special Key is not supported yet!!!!");
                    }
                    else if(key!=null){
                        callEmit(key, value, doc, emitFn);
                    }
                }
            };
            if(mapBlock!=null)
                mapBlock.map(doc, emitter);
            Log.w(TAG, "[MapReduceBridge.call()] END");
        }

        private void callEmit(Object key,Object value, Map<String, Object> doc, EmitFn emitFn){
            Collatable collKey = new Collatable();
            add(collKey, key);
            Collatable collValue = new Collatable();
            if(value == doc)
                collValue.addSpecial();
            else if(value != null)
                add(collValue, value);
            emitFn.call(collKey, collValue);
        }
    }


    /** utility method for Collatable */
    public static Collatable add(Collatable collatable, Object obj){
        if(collatable == null) return null;
        if(obj == null)
            return collatable.addNull();
        else if(obj instanceof String){
            return collatable.add((String)obj);
        }
        else if(obj instanceof Boolean){
            return collatable.add((Boolean)obj);
        }
        else if(obj instanceof Double){
            return collatable.add((Double)obj);
        }
        else if(obj instanceof Slice){
            return collatable.add((Slice)obj);
        }
        else if(obj instanceof Collatable){
            return collatable.add((Collatable)obj);
        }
        else{
            return collatable;
        }
    }


    /**
     * in CBLView.h
     */
    public enum CBLViewIndexType {
        kCBLUnknown(-1),
        kCBLMapReduceIndex(1), /**< Regular map/reduce index with JSON keys. */
        kCBLFullTextIndex(2),  /**< Keys must be strings and will be indexed by the words they contain. */
        kCBLGeoIndex(3);       /**< Geo-query index; not supported yet. */
        private int value;
        private CBLViewIndexType(int value) {
            this.value = value;
        }
        public int getValue() {
            return value;
        }
    }

    // from CBLView.h
    private DatabaseCBForest weakDB = null;
    private String name = null;
    //private Mapper mapBlock = null;
    //private Reducer reduceBlock = null;

    // from CBLView.m
    private String path = null;
    private com.couchbase.lite.cbforest.Database indexDB = null;
    private com.couchbase.lite.cbforest.MapReduceIndex index = null;
    private CBLViewIndexType indexType = CBLViewIndexType.kCBLUnknown;
    private MapReduceBridge mapReduceBridge;

    public static final String  kViewIndexPathExtension = "viewindex";


    //================================================================================
    // CBLView (CBLView.m)
    //================================================================================


    /**
     * Constructor
     *
     * in CBLView.m
     * - (instancetype) initWithDatabase: (CBLDatabase*)db name: (NSString*)name create: (BOOL)create
     */
    @InterfaceAudience.Private
    protected ViewCBForest(DatabaseCBForest db, String name, boolean create) throws CouchbaseLiteException{
        assert(db!=null);
        assert(name!=null&&name.length() > 0);
        this.weakDB = db;
        this.name = name;
        this.path = new File(db.getPath(), viewNameToFileName(name)).toString();
        Log.w(TAG, "path => " + path);
        this.indexType = CBLViewIndexType.kCBLUnknown;
        this.mapReduceBridge = new MapReduceBridge();

        File file = new File(this.path);
        if(!file.exists()){
            if(!create || openIndex(OpenFlags.FDB_OPEN_FLAG_CREATE) == null)
                throw new CouchbaseLiteException(Status.BAD_REQUEST);
            closeIndexSoon();
        }
    }


    /**
     * in CBLView.m
     * static inline NSString* viewNameToFileName(NSString* viewName)
     */
    protected static String viewNameToFileName(String viewName){
        if(viewName.startsWith(".") || viewName.indexOf(":") > 0)
            return null;
        viewName = viewName.replaceAll("/", ":");
        return viewName + "." + kViewIndexPathExtension;
    }
    protected static String fileNameToViewName(String fileName){
        if(!fileName.endsWith(kViewIndexPathExtension))
            return null;
        if(fileName.startsWith("."))
            return null;
        String viewName = fileName.substring(0, fileName.indexOf("."));
        viewName = viewName.replaceAll(":", "/");
        return viewName;
    }


    /**
     * Get the name of the view.
     *
     * in CBLView.m
     * @synthesize name=_name;
     * readonly
     */
    @InterfaceAudience.Public
    public String getName() {
        return name;
    }


    /**
     * in CBLView.m
     * - (NSString*) description
     */
    public String toString(){
        return String.format("%s[%s/%s]", getClass().getName(), weakDB.getName(), name);
    }

    /**
     * Get the database that owns this view.
     *
     * in CBLView.m
     * - (CBLDatabase*) database
     * readonly
     */
    @InterfaceAudience.Public
    public Database getDatabase() {
        return weakDB;
    };

    /**
     * in CBLView.m
     * - (MapReduceIndex*) index
     */
    @InterfaceAudience.Public
    private MapReduceIndex getIndex(){
        closeIndexSoon();
        return index!=null?index : openIndex(OpenFlags.FDB_OPEN_FLAG_CREATE);
    }

    /**
     * in CBLView.m
     * - (MapReduceIndex*) openIndexWithOptions: (Database::openFlags)options
     */
    @InterfaceAudience.Public
    private MapReduceIndex openIndex(OpenFlags options){
        assert(indexDB != null);
        assert(index != null);

        com.couchbase.lite.cbforest.Config config = com.couchbase.lite.cbforest.Database.defaultConfig();
        indexDB = new com.couchbase.lite.cbforest.Database(path, options, config);
        if(indexDB == null) {
            Log.w(TAG, String.format("Unable to create index database of %s", this.toString()));
            return null;
        }
        index = new com.couchbase.lite.cbforest.MapReduceIndex(indexDB, "index", weakDB.getForestDB());
        if(index == null) {
            Log.w(TAG, String.format("Unable to open index of %s", this.toString()));
            return null;
        }
        Log.w(TAG, String.format("%s: Opened index %s (type %d)", this.toString(), index.toString(), index.indexType()));
        return index;
    }

    /**
     * in CBLView.m
     * - (void) closeIndex
     */
    @InterfaceAudience.Public
    private void closeIndex(){
        // cancel close index soon
        if(indexDB != null) {
            Log.w(TAG, String.format("%s: Closing index db", this.toString()));
            indexDB.delete();
            indexDB = null;
        }
        if(index!=null){
            index.delete();
            index = null;
        }
    }
    /**
     * in CBLView.m
     * - (void) closeIndexSoon
     */
    @InterfaceAudience.Public
    private void closeIndexSoon(){
        // TODO
    }

    /**
     * in CBLView.m
     *- (void) databaseClosing
     */
    @InterfaceAudience.Public
    public void databaseClosing() {
        closeIndex();
        weakDB = null;
    }

    /**
     * in CBLView.m
     * - (void) deleteIndex
     */
    @InterfaceAudience.Public
    public void deleteIndex() {
        if(indexDB!=null){
            Transaction t = new Transaction(indexDB);
            try {
                index.erase(t);
            }
            finally {
                t.delete();
            }
        }
    }
    /**
     * in CBLView.m
     * - (void) deleteIndex
     */
    @InterfaceAudience.Public
    public void deleteView(){
        closeIndex();
        FileDirUtils.deleteRecursive(new File(path));
        weakDB.forgetViewNamed(name);
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Public
    public void delete() {
        deleteView();
    }

    // pragma mark - CONFIGURATION:

    /**
     * The map function that controls how index rows are created from documents.
     *
     * in CBLView.m
     * - (CBLMapBlock) mapBlock
     *
     * readonly
     */
    @InterfaceAudience.Public
    public Mapper getMap() {
        return (Mapper)weakDB.getShared().valueFor("map", name, weakDB.getName());
    }

    /**
     * in CBLView.m
     * - (NSString*) mapVersion
     * @return
     */
    protected String getMapVersion(){
        return (String)weakDB.getShared().valueFor("mapVersion", name, weakDB.getName());
    }

    /**
     * The optional reduce function, which aggregates together multiple rows.
     *
     * readonly
     */
    @InterfaceAudience.Public
    public Reducer getReduce() {
        return (Reducer)weakDB.getShared().valueFor("reduce", name, weakDB.getName());
    }

    /**
     * Defines a view's functions.
     *
     * The view's definition is given as a class that conforms to the Mapper or
     * Reducer interface (or null to delete the view). The body of the block
     * should call the 'emit' object (passed in as a paramter) for every key/value pair
     * it wants to write to the view.
     *
     * Since the function itself is obviously not stored in the database (only a unique
     * string idenfitying it), you must re-define the view on every launch of the app!
     * If the database needs to rebuild the view but the function hasn't been defined yet,
     * it will fail and the view will be empty, causing weird problems later on.
     *
     * It is very important that this block be a law-abiding map function! As in other
     * languages, it must be a "pure" function, with no side effects, that always emits
     * the same values given the same input document. That means that it should not access
     * or change any external state; be careful, since callbacks make that so easy that you
     * might do it inadvertently!  The callback may be called on any thread, or on
     * multiple threads simultaneously. This won't be a problem if the code is "pure" as
     * described above, since it will as a consequence also be thread-safe.
     *
     * in CBLView.m
     * - (BOOL) setMapBlock: (CBLMapBlock)mapBlock
     *          reduceBlock: (CBLReduceBlock)reduceBlock
     *              version: (NSString *)version
     */
    @InterfaceAudience.Public
    public boolean setMapReduce(Mapper mapBlock, Reducer reduceBlock, String version) {
        assert (mapBlock != null);
        assert (version  != null);

        boolean changed = !version.equals(this.getMapVersion());

        Shared shared = weakDB.getShared();
        String dbName = weakDB.getName();

        shared.setValue(mapBlock,    "map",        name, dbName);
        shared.setValue(version,     "mapVersion", name, dbName);
        shared.setValue(reduceBlock, "reduce",     name, dbName);

        if(changed){
            postPublicChangeNotification();
        }

        return changed;
    }

    /**
     * Defines a view that has no reduce function.
     * See setMapReduce() for more information.
     *
     * in CBLView.m
     * - (BOOL) setMapBlock: (CBLMapBlock)mapBlock version: (NSString *)version
     */
    public boolean setMap(Mapper mapBlock, String version) {
        return setMapReduce(mapBlock, null, version);
    }

    // pragma mark - INDEXING:

    /**
     * in CBLView.m
     * - (NSArray*) viewsInGroup
     */
    @InterfaceAudience.Private
    List<ViewCBForest> viewsInGroup(){
        int slashLoc = name.indexOf("/");
        int slashLen = slashLoc == -1 ? 0 : 1;
        if(slashLen > 0){
            // Return all the views whose name starts with the same prefix before the slash:
            String prefix = name.substring(0, slashLoc + slashLen);
            List<ViewCBForest> results = new ArrayList<ViewCBForest>();
            List<View> views = weakDB.getAllViews();
            for(View view:views){
                if(view.getName().startsWith(prefix))
                    if(view instanceof ViewCBForest)
                        results.add((ViewCBForest)view);
            }
            return results;
        }
        else{
            // Without GROUP_VIEWS_BY_DEFAULT, views with no "/" in the name aren't in any group:
            return Arrays.asList(this);
        }
    }

    /**
      * in CBLView.m
     * - (void) setupIndex
     */
    @InterfaceAudience.Private
    public void setupIndex() {
        this.mapReduceBridge.setMapBlock(getMap());
        this.mapReduceBridge.setViewName(this.name);
        this.mapReduceBridge.setIndexType(this.indexType);
        this.getIndex(); // open db
        {
            Transaction t = new Transaction(indexDB);
            try{
                index.setup(t, indexType.getValue(), mapReduceBridge, getMapVersion());
            }
            finally {
                t.delete();
            }
        }
    }

    /**
     * Updates the view's index, if necessary. (If no changes needed, returns kCBLStatusNotModified.)
     *
     * in CBLView.m
     * - (CBLStatus) updateIndex
     */
    @InterfaceAudience.Private
    public void updateIndex() throws CouchbaseLiteException {
        updateIndex(viewsInGroup());
    }

    /**
     * in CBLView.m
     * - (CBLStatus) updateIndexAlone
     */
    @InterfaceAudience.Private
    public void updateIndexAlone() throws CouchbaseLiteException {
        updateIndex(Arrays.asList(this));
    }
    /**
     * Updates the view's index (incrementally) if necessary.
     *
     * in CBLView.m
     * - (CBLStatus) updateIndexes: (NSArray*)views
     */
    @InterfaceAudience.Private
    public void updateIndex(List<ViewCBForest> views) throws CouchbaseLiteException {
        Log.v(Log.TAG, "Re-indexing view: %s", name);

        VectorMapReduceIndex indexes = new VectorMapReduceIndex();
        for(ViewCBForest view : views){
            view.setupIndex();
            Mapper mapBlock = view.getMap();
            assert (mapBlock != null);
            MapReduceIndex index = view.getIndex();
            if(index == null)
                throw new CouchbaseLiteException(Status.NOT_FOUND);
            indexes.add(index);
        }

        this.getIndex(); // open db

        boolean updated = false;
        Transaction t = new Transaction(indexDB);
        CocoaIndexer indexer = new CocoaIndexer(indexes, t);
        try {
            indexer.triggerOnIndex(index);
            updated = indexer.run();
            if(updated==false)
                throw new CouchbaseLiteException(Status.NOT_MODIFIED);
        }finally {
            indexer.delete();
            t.delete();
        }
    }



    public String toJSONString(Object object) {
        return null;
    }

    public TDViewCollation getCollation() {
        return null;
    }

    public Cursor resultSetWithOptions(QueryOptions options) {
        return null;
    }



    public List<QueryRow> queryWithOptions(QueryOptions options) throws CouchbaseLiteException {
        return null;
    }

    /**
     * in CBLView.m
     * - (void) postPublicChangeNotification
     */
    public void postPublicChangeNotification(){
        //TODO
    }

    // pragma mark - QUERYING:

    /**
     * Get the last sequence number indexed so far.
     *
     * in CBLView.m
     * - (SequenceNumber) lastSequenceIndexed
     */
    @InterfaceAudience.Public
    public long getLastSequenceIndexed() {
        setupIndex(); // in case the _mapVersion changed, invalidating the index
        return index.lastSequenceIndexed().longValue();
    }
    /**
     * in CBLView.m
     * - (SequenceNumber) lastSequenceChangedAt
     */
    @InterfaceAudience.Public
    public long getLastSequenceChangedAt() {
        setupIndex(); // in case the _mapVersion changed, invalidating the index
        return index.lastSequenceChangedAt().longValue();
    }
    /**
     * Is the view's index currently out of date?
     *
     * in CBLView.m
     * - (BOOL) stale
     *
     * reaonly
     */
    @InterfaceAudience.Public
    public boolean isStale() {
        return (getLastSequenceIndexed() < weakDB.getLastSequenceNumber());
    }

    /**
     * Creates a new query object for this view. The query can be customized and then executed.
     *
     * in CBLView.m
     * - (CBLQuery*) createQuery
     */
    @InterfaceAudience.Public
    public Query createQuery() {
        return new Query(getDatabase(), this);
    }


    //================================================================================
    // CBLView+Querying (CBLView+Querying.m)
    //================================================================================

    /**
     * Starts a view query, returning a CBForest enumerator.
     *
     * in CBLView+Querying.m
     * - (IndexEnumerator) _runForestQueryWithOptions: (CBLQueryOptions*)options
     */
    private IndexEnumerator runForestQueryWithOptions(QueryOptions options){

        MapReduceIndex index = getIndex();
        DocEnumerator.Options forestOpts = DocEnumerator.Options.getDef();
        forestOpts.setSkip(options.getSkip());
        if(options.getLimit()>0)
            forestOpts.setLimit(options.getLimit());
        forestOpts.setDescending(options.isDescending());
        forestOpts.setInclusiveStart(options.isInclusiveStart());
        forestOpts.setInclusiveEnd(options.isInclusiveEnd());

        if(options.getKeys() != null && options.getKeys().size() > 0){
            VectorKeyRange collatableKeys = new VectorKeyRange();
            for(Object obj : options.getKeys()){
                Collatable collKey = new Collatable();
                add(collKey, obj);
                collatableKeys.add(new KeyRange(collKey));
            }
            return new IndexEnumerator(index, collatableKeys, forestOpts);
        }
        else {
            Object endKey = keyForPrefixMatch(options.getEndKey(), options.getPrefixMatchLevel());
            Log.w(TAG, "endKey => " + endKey);

            Collatable collStartKey = new Collatable();
            if(options.getStartKey() != null)
                add(collStartKey, options.getStartKey());

            Collatable collEndKey = new Collatable();
            if(endKey != null)
                add(collEndKey, endKey);

            Slice startKeyDocID = null;
            if(options.getStartKeyDocId() == null)
                startKeyDocID = Slice.getNull();
            else
                startKeyDocID = new Slice(options.getStartKeyDocId().getBytes());

            Slice endKeyDocID = null;
            if(options.getEndKeyDocId() == null)
                endKeyDocID = Slice.getNull();
            else
                endKeyDocID = new Slice(options.getEndKeyDocId().getBytes());

            return new IndexEnumerator(index,
                    collStartKey,
                    startKeyDocID,
                    collEndKey,
                    endKeyDocID,
                    forestOpts);
        }
    }

    // pragma mark - UTILITIES:

    /**
     * Changes a maxKey into one that also extends to any key it matches as a prefix.
     *
     * in CBLView+Querying.m
     * static id keyForPrefixMatch(id key, unsigned depth)
     */
    @InterfaceAudience.Private
    public static Object keyForPrefixMatch(Object key, int depth){
        if(depth < 1)
            return key;
        if(key instanceof String){
            return (key + "\uFFFF");
        }
        else if(key instanceof List){
            ArrayList<Map<String, Object>> nuKey = new ArrayList<Map<String, Object>>((List<Map<String, Object>>)key);
            if(depth == 1){
                nuKey.add(new HashMap<String, Object>());
            }
            else{
                Object lastObject = nuKey.get(nuKey.size() - 1);
                lastObject = keyForPrefixMatch(lastObject, depth - 1);
                nuKey.set(nuKey.size() - 1, (Map<String, Object>)lastObject);
            }
            return nuKey;
        }
        else{
            return key;
        }
    }
    /**
     * This is really just for unit tests & debugging
     *
     * in CBLView+Querying.m
     * - (NSArray*) dump
     */
    @InterfaceAudience.Private
    public List<Map<String, Object>> dump() {
        MapReduceIndex index = getIndex();
        if(index == null)
            return null;
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        IndexEnumerator e = runForestQueryWithOptions(new QueryOptions());
        do{
            Map<String, Object> dict = new HashMap<String, Object>();
            try {
                Log.w(TAG, "key => "+ Manager.getObjectMapper().writeValueAsString(new String(e.key().readString().getBuf())));
                dict.put("key",   Manager.getObjectMapper().writeValueAsString(new String(e.key().readString().getBuf())));
                dict.put("value", Manager.getObjectMapper().writeValueAsString(new String(e.key().readString().getBuf())));
            } catch (IOException e1) {
                Log.e(TAG, e1.getMessage());
                return null;
            }
            dict.put("seq",   e.sequence().intValue());
            result.add(dict);
            Log.w(TAG, dict.toString());
        }while(e.next());
        return result;
    }


    //================================================================================
    // No longer supported by CBL forestdb version
    //================================================================================

    /**
     * backward compatibility
     *
     * in CBLView.m
     * - (void) setCollation: (CBLViewCollation)collation
     */
    @InterfaceAudience.Private
    public void setCollation(TDViewCollation collation) {
    }

    @InterfaceAudience.Private
    public int getViewId() {
        return 0;
    }
}
