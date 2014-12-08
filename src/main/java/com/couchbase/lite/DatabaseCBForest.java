package com.couchbase.lite;


import com.couchbase.lite.cbforest.Config;
import com.couchbase.lite.cbforest.ContentOptions;
import com.couchbase.lite.cbforest.DocEnumerator;
import com.couchbase.lite.cbforest.OpenFlags;
import com.couchbase.lite.cbforest.RevIDBuffer;
import com.couchbase.lite.cbforest.Slice;
import com.couchbase.lite.cbforest.Transaction;
import com.couchbase.lite.cbforest.VectorRevID;
import com.couchbase.lite.cbforest.VersionedDocument;
import com.couchbase.lite.internal.AttachmentInternal;
import com.couchbase.lite.internal.Body;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.storage.SQLException;
import com.couchbase.lite.storage.SQLiteStorageEngine;
import com.couchbase.lite.support.Base64;
import com.couchbase.lite.support.FileDirUtils;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.PersistentCookieStore;
import com.couchbase.lite.util.CollectionUtils;
import com.couchbase.lite.util.Log;
import com.couchbase.lite.util.StreamUtils;
import com.couchbase.lite.util.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by hideki on 11/22/14.
 */
public class DatabaseCBForest implements Database {
    /** static constructor */
    static {
        try{
            System.loadLibrary("cbforest");
            Log.w("DatabaseCBForest", "load libcbforest OK !!!");
        }
        catch(Exception e){
            Log.e("DatabaseCBForest", "Failed to load libcbforest !!!");
        }
    }

    // Default value for maxRevTreeDepth, the max rev depth to preserve in a prune operation
    private static final int DEFAULT_MAX_REVS = Integer.MAX_VALUE;
    public final static String TAG ="DatabaseCBForest";

    private Map<String, Validator> validations = null;
    final private CopyOnWriteArrayList<ChangeListener> changeListeners;

    private List<DocumentChange> changesToNotify;
    private boolean postingChangeNotifications = false;

    /**
     * Variables defined in CBLDatabase.h or .m
     */
    private Cache<String, Document> docCache;
    private Set<Replication> allReplicators = null;
    private int maxRevTreeDepth = DEFAULT_MAX_REVS;

    /**
     * Variables defined in CBLDatabase+Internal.h
     */
    private String dir = null; // NSString* _dir;
    private String name = null;
    private Manager manager = null;
    private com.couchbase.lite.cbforest.Database forest = null;
    private com.couchbase.lite.cbforest.Transaction forestTransaction = null;
    private com.couchbase.lite.cbforest.Database localDocs = null;
    private boolean readOnly = false;
    private boolean isOpen = false;
    private int transactionLevel = 0;
    private Map<String, View> views = null;
    private BlobStore attachments = null;
    private Map<String, BlobStoreWriter> pendingAttachmentsByDigest = null;
    private Set<Replication> activeReplicators;
    private long startTime = 0;






    private static final Set<String> KNOWN_SPECIAL_KEYS;
    static {
        KNOWN_SPECIAL_KEYS = new HashSet<String>();
        KNOWN_SPECIAL_KEYS.add("_id");
        KNOWN_SPECIAL_KEYS.add("_rev");
        KNOWN_SPECIAL_KEYS.add("_attachments");
        KNOWN_SPECIAL_KEYS.add("_deleted");
        KNOWN_SPECIAL_KEYS.add("_revisions");
        KNOWN_SPECIAL_KEYS.add("_revs_info");
        KNOWN_SPECIAL_KEYS.add("_conflicts");
        KNOWN_SPECIAL_KEYS.add("_deleted_conflicts");
        KNOWN_SPECIAL_KEYS.add("_local_seq");
        KNOWN_SPECIAL_KEYS.add("_removed");
    }

    //TODO: implement details
    public boolean open() {
        if(isOpen) return true;

        Log.w(TAG, "Opening " + toString());

        // Create the database directory:
        File dirFile = new File(dir);
        if(!dirFile.exists())
            if(!dirFile.mkdir())
                return false;

        String forestPath = new File(dir, "db.forest").getAbsolutePath();
        Log.w(TAG, "forestPath => " + forestPath);
        OpenFlags options = readOnly ? OpenFlags.FDB_OPEN_FLAG_RDONLY : OpenFlags.FDB_OPEN_FLAG_CREATE;

        Config config = com.couchbase.lite.cbforest.Database.defaultConfig();
        // TODO - update config

        forest = new com.couchbase.lite.cbforest.Database(forestPath,
                options,
                config);
        // TODO - add Log Callback

        // First-time setup:
        String privateUUID = privateUUID();
        if(privateUUID == null){
            setInfo("privateUUID", Misc.TDCreateUUID());
            setInfo("publicUUID", Misc.TDCreateUUID());
        }

        // Open attachment store:
        try {
            attachments = new BlobStore(getAttachmentStorePath());
        } catch (IllegalArgumentException e) {
            Log.e(Database.TAG, "Could not initialize attachment store", e);
            forest.delete();
            return false;
        }

        isOpen = true;

        // Listen for _any_ CBLDatabase changing, so I can detect changes made to my database
        // file by other instances (running on other threads presumably.)
        // TODO - listener

        return isOpen;
    }

    /**
     * Get all the replicators associated with this database.
     */
    @InterfaceAudience.Public
    public List<Replication> getAllReplications() {
        List<Replication> allReplicatorsList =  new ArrayList<Replication>();
        if (allReplicators != null) {
            allReplicatorsList.addAll(allReplicators);
        }
        return allReplicatorsList;
    }

    /**
     * Compacts the database file by purging non-current JSON bodies, pruning revisions older than
     * the maxRevTreeDepth, deleting unused attachment files, and vacuuming the SQLite database.
     */
    @InterfaceAudience.Public
    public void compact() throws CouchbaseLiteException {
        forest.compact();

        // TODO!!!!
    }

    // NOTE: Same with SQLite?
    public Document getDocument(String documentId) {
        if (documentId == null || documentId.length() == 0) {
            return null;
        }
        Document doc = docCache.get(documentId);
        if (doc == null) {
            doc = new Document(this, documentId);
            if (doc == null) {
                return null;
            }
            docCache.put(documentId, doc);
        }
        return doc;
    }

    public Document getExistingDocument(String documentId) {
        return null;
    }

    // NOTE: Same with SQLite?
    public Document createDocument() {
        return getDocument(Misc.TDCreateUUID());
    }

    public Map<String, Object> getExistingLocalDocument(String documentId) {
        return null;
    }

    public Query createAllDocumentsQuery() {
        return null;
    }

    public View getView(String name) {
        return null;
    }

    public View getExistingView(String name) {
        return null;
    }

    public Validator getValidation(String name) {
        Validator result = null;
        if(validations != null) {
            result = validations.get(name);
        }
        return result;
    }

    public void setValidation(String name, Validator validator) {
        if(validations == null) {
            validations = new HashMap<String, Validator>();
        }
        if (validator != null) {
            validations.put(name, validator);
        }
        else {
            validations.remove(name);
        }
    }

    public ReplicationFilter getFilter(String filterName) {
        return null;
    }

    public void setFilter(String filterName, ReplicationFilter filter) {

    }



    public Future runAsync(AsyncTask asyncTask) {
        return null;
    }

    public Replication createPushReplication(URL remote) {
        return null;
    }

    public Replication createPullReplication(URL remote) {
        return null;
    }

    // NOTE: Same with SQLite?
    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener listener) {
        changeListeners.addIfAbsent(listener);
    }

    // NOTE: Same with SQLite?
    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener listener) {
        changeListeners.remove(listener);
    }

    // NOTE: Same with SQLite?
    public int getMaxRevTreeDepth() {
        return maxRevTreeDepth;
    }
    // NOTE: Same with SQLite?
    public void setMaxRevTreeDepth(int maxRevTreeDepth) {
        this.maxRevTreeDepth = maxRevTreeDepth;
    }
    // NOTE: Same with SQLite?
    public Document getCachedDocument(String documentID) {
        return docCache.get(documentID);
    }
    // NOTE: Same with SQLite?
    public void clearDocumentCache() {
        docCache.clear();
    }

    public List<Replication> getActiveReplications() {
        return null;
    }

    // NOTE: Same with SQLite?
    public void removeDocumentFromCache(Document document) {
        docCache.remove(document.getId());
    }

    public boolean initialize(String statements) {
        return false;
    }

    public SQLiteStorageEngine getDatabase() {
        return null;
    }



    public byte[] appendDictToJSON(byte[] json, Map<String, Object> dict) {
        return new byte[0];
    }

    public Map<String, Object> extraPropertiesForRevision(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }

    public void expandStoredJSONIntoRevisionWithAttachments(byte[] json, RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) {

    }

    public Map<String, Object> documentPropertiesFromJSON(byte[] json, String docId, String revId, boolean deleted, long sequence, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }





    public long getDocNumericID(String docId) {
        return 0;
    }

    // TODO: Do we need this?
    public RevisionList getAllRevisionsOfDocumentID(String docId, long docNumericID, boolean onlyCurrent) {
        return null;
    }

    public List<String> getConflictingRevisionIDsOfDocID(String docID) {
        return null;
    }

    public List<String> getPossibleAncestorRevisionIDs(RevisionInternal rev, int limit, AtomicBoolean hasAttachment) {
        return null;
    }

    public String findCommonAncestorOf(RevisionInternal rev, List<String> revIDs) {
        return null;
    }

    @InterfaceAudience.Private
    public List<RevisionInternal> getRevisionHistory(RevisionInternal rev) {
        String docId = rev.getDocId();
        String revId = rev.getRevId();
        VersionedDocument doc = new VersionedDocument(forest, new Slice(docId.getBytes()));
        com.couchbase.lite.cbforest.Revision revision = doc.get(new RevIDBuffer(new Slice(revId.getBytes())));
        List<RevisionInternal> history = ForestBridge.getRevisionHistory(docId, revision);
        doc.delete();
        return history;
    }

    /**
     * Returns the revision history as a _revisions dictionary, as returned by the REST API's ?revs=true option.
     */
    @InterfaceAudience.Private
    public Map<String, Object> getRevisionHistoryDict(RevisionInternal rev) {
        return DatabaseUtil.makeRevisionHistoryDict(getRevisionHistory(rev));
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Private
    public RevisionList changesSince(long lastSeq, ChangesOptions options, ReplicationFilter filter) {
        return changesSince(lastSeq, options, filter, null);
    }

    public String getDesignDocFunction(String fnName, String key, List<String> outLanguageList) {
        return null;
    }

    public View registerView(View view) {
        return null;
    }

    public List<QueryRow> queryViewNamed(String viewName, QueryOptions options, List<Long> outLastSequence) throws CouchbaseLiteException {
        return null;
    }

    public View makeAnonymousView() {
        return null;
    }

    public List<View> getAllViews() {
        return null;
    }

    public Status deleteViewNamed(String name) {
        return null;
    }

    public int getDeletedColumnIndex(QueryOptions options) {
        return 0;
    }

    public Map<String, Object> getAllDocs(QueryOptions options) throws CouchbaseLiteException {
        return null;
    }

    public String winningRevIDOfDoc(long docNumericId, AtomicBoolean outIsDeleted, AtomicBoolean outIsConflict) throws CouchbaseLiteException {
        return null;
    }



    public void copyAttachmentNamedFromSequenceToSequence(String name, long fromSeq, long toSeq) throws CouchbaseLiteException {

    }

    public Attachment getAttachmentForSequence(long sequence, String filename) throws CouchbaseLiteException {
        return null;
    }

    public boolean sequenceHasAttachments(long sequence) {
        return false;
    }

    public Map<String, Object> getAttachmentsDictForSequenceWithContent(long sequence, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }


    public void stubOutAttachmentsIn(RevisionInternal rev, int minRevPos) {

    }

    public boolean inlineFollowingAttachmentsIn(RevisionInternal rev) {
        return false;
    }







    public Status garbageCollectAttachments() {
        return null;
    }

    public String generateIDForRevision(RevisionInternal rev, byte[] json, Map<String, AttachmentInternal> attachments, String previousRevisionId) {
        // NOTE: NOT IMPLEMENTED
        // see: generateRevIDForJSON()
        return null;
    }

    /**
     * Given an existing revision ID, generates an ID for the next revision.
     * Returns nil if prevID is invalid.
     */
    @InterfaceAudience.Private
    public String generateRevIDForJSON(byte[] json, boolean deleted, String previousRevisionId) {

        MessageDigest md5Digest;

        // Revision IDs have a generation count, a hyphen, and a UUID.

        int generation = 0;
        if(previousRevisionId != null) {
            generation = RevisionInternal.generationFromRevID(previousRevisionId);
            if(generation == 0) {
                return null;
            }
        }

        // Generate a digest for this revision based on the previous revision ID, document JSON,
        // and attachment digests. This doesn't need to be secure; we just need to ensure that this
        // code consistently generates the same ID given equivalent revisions.

        try {
            md5Digest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        int length = 0;
        if (previousRevisionId != null) {
            byte[] prevIDUTF8 = previousRevisionId.getBytes(Charset.forName("UTF-8"));
            length = prevIDUTF8.length;
        }
        if (length > 0xFF) {
            return null;
        }
        byte lengthByte = (byte) (length & 0xFF);
        byte[] lengthBytes = new byte[] { lengthByte };

        md5Digest.update(lengthBytes);

        int isDeleted = ((deleted != false) ? 1 : 0);
        byte[] deletedByte = new byte[] { (byte) isDeleted };
        md5Digest.update(deletedByte);

        if (json != null) {
            md5Digest.update(json);
        }
        byte[] md5DigestResult = md5Digest.digest();

        String digestAsHex = Utils.bytesToHex(md5DigestResult);

        int generationIncremented = generation + 1;
        return String.format("%d-%s", generationIncremented, digestAsHex).toLowerCase();

    }

    public long insertDocumentID(String docId) {
        return 0;
    }

    public long getOrInsertDocNumericID(String docId) {
        return 0;
    }

    // TODO: Same??
    public byte[] encodeDocumentJSON(RevisionInternal rev) {
        Map<String,Object> origProps = rev.getProperties();
        if(origProps == null) {
            return null;
        }

        List<String> specialKeysToLeave = Arrays.asList(
                "_removed",
                "_replication_id",
                "_replication_state",
                "_replication_state_time");

        // Don't allow any "_"-prefixed keys. Known ones we'll ignore, unknown ones are an error.
        Map<String,Object> properties = new HashMap<String,Object>(origProps.size());
        for (String key : origProps.keySet()) {
            boolean shouldAdd = false;
            if(key.startsWith("_")) {
                if(!KNOWN_SPECIAL_KEYS.contains(key)) {
                    Log.e(TAG, "Database: Invalid top-level key '%s' in document to be inserted", key);
                    return null;
                }
                if (specialKeysToLeave.contains(key)) {
                    shouldAdd = true;
                }
            } else {
                shouldAdd = true;
            }
            if (shouldAdd) {
                properties.put(key, origProps.get(key));
            }
        }

        byte[] json = null;
        try {
            json = Manager.getObjectMapper().writeValueAsBytes(properties);
        } catch (Exception e) {
            Log.e(Database.TAG, "Error serializing " + rev + " to JSON", e);
        }
        return json;
    }

    // TODO: No longer used?
    public void notifyChange(RevisionInternal rev, RevisionInternal winningRev, URL source, boolean inConflict) {
    }

    public long insertRevision(RevisionInternal rev, long docNumericID, long parentSequence, boolean current, boolean hasAttachments, byte[] data) {
        return 0;
    }



    public Replication getActiveReplicator(URL remote, boolean push) {
        return null;
    }

    public Replication getReplicator(URL remote, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        return null;
    }

    public Replication getReplicator(String sessionId) {
        return null;
    }

    public Replication getReplicator(URL remote, HttpClientFactory httpClientFactory, boolean push, boolean continuous, ScheduledExecutorService workExecutor) {
        return null;
    }

    public String lastSequenceWithCheckpointId(String checkpointId) {
        return null;
    }

    public boolean setLastSequence(String lastSequence, String checkpointId, boolean push) {
        return false;
    }

    public String getLastSequenceStored(String checkpointId, boolean push) {
        return null;
    }

    public int findMissingRevisions(RevisionList touchRevs) throws SQLException {
        return 0;
    }

    public Query slowQuery(Mapper map) {
        return null;
    }

    public RevisionInternal getParentRevision(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> purgeRevisions(Map<String, List<String>> docsToRevs) {
        return null;
    }

    public boolean replaceUUIDs() {
        return false;
    }

    @InterfaceAudience.Private
    public long getStartTime() {
        return this.startTime;
    }

    /**
     * Set the database's name.
     */
    @InterfaceAudience.Private
    public void setName(String name) {
        this.name = name;
    }

    // TODO not used for Forestdb
    public int pruneRevsToMaxDepth(int maxDepth) throws CouchbaseLiteException {
        return 0;
    }

    /**
     * Is the database open?
     */
    @InterfaceAudience.Private
    public boolean isOpen() {
        return isOpen;
    }

    public void addReplication(Replication replication) {

    }

    public void forgetReplication(Replication replication) {

    }

    public void addActiveReplication(Replication replication) {

    }

    public PersistentCookieStore getPersistentCookieStore() {
        return null;
    }

    // SAME
    @InterfaceAudience.Private
    private void postChangeNotifications() {
        // This is a 'while' instead of an 'if' because when we finish posting notifications, there
        // might be new ones that have arrived as a result of notification handlers making document
        // changes of their own (the replicator manager will do this.) So we need to check again.
        while (transactionLevel == 0 && isOpen() && !postingChangeNotifications
                && changesToNotify.size() > 0)
        {

            try {
                postingChangeNotifications = true; // Disallow re-entrant calls

                List<DocumentChange> outgoingChanges = new ArrayList<DocumentChange>();
                outgoingChanges.addAll(changesToNotify);
                changesToNotify.clear();

                // TODO: change this to match iOS and call cachedDocumentWithID
                /*
                BOOL external = NO;
                for (CBLDatabaseChange* change in changes) {
                    // Notify the corresponding instantiated CBLDocument object (if any):
                    [[self _cachedDocumentWithID: change.documentID] revisionAdded: change];
                    if (change.source != nil)
                        external = YES;
                }
                */

                boolean isExternal = false;
                for (DocumentChange change: outgoingChanges) {
                    Document document = getDocument(change.getDocumentId());
                    document.revisionAdded(change);
                    if (change.getSourceUrl() != null) {
                        isExternal = true;
                    }
                }

                ChangeEvent changeEvent = new ChangeEvent(this, isExternal, outgoingChanges);

                for (ChangeListener changeListener : changeListeners) {
                    changeListener.changed(changeEvent);
                }

            } catch (Exception e) {
                Log.e(Database.TAG, this + " got exception posting change notifications", e);
            } finally {
                postingChangeNotifications = false;
            }

        }


    }
    private DocumentChange changeWithNewRevision(RevisionInternal inRev, boolean isWinningRev,
                                                 VersionedDocument doc, URL source){

        RevisionInternal winningRev = inRev;
        if(isWinningRev == false){
            com.couchbase.lite.cbforest.Revision winningRevision = doc.currentRevision();
            String winningRevID = new String(winningRevision.getRevID().getBuf());
            if(!winningRevID.equals(inRev.getRevId().toString())){
                winningRev = new RevisionInternal(inRev.getDocId(), winningRevID, winningRevision.isDeleted());
            }
        }
        return new DocumentChange(inRev, winningRev, doc.hasConflict(), source);
    }
    // SAME
    private void notifyChange(DocumentChange documentChange) {
        if (changesToNotify == null) {
            changesToNotify = new ArrayList<DocumentChange>();
        }
        changesToNotify.add(documentChange);

        postChangeNotifications();
    }

    //================================================================================
    // CBLDatabase (API/CBLDatabase.m)
    //================================================================================

    /**
     * CBLDatabase.m
     * synthesize name=_name
     */
    @InterfaceAudience.Public
    public String getName() {
        return name;
    }

    /**
     * CBLDatabase.m
     * synthesize dir=_dir,
     */
    @InterfaceAudience.Public
    public String getPath() {
        return dir;
    }

    /**
     * CBLDatabase.m
     * synthesize manager=_manager
     */
    @InterfaceAudience.Public
    public Manager getManager() {
        return manager;
    }

    /**
     * in CBLDatabase.m
     * - (BOOL) deleteDatabase: (NSError**)outError
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public void delete() throws CouchbaseLiteException {
        Log.w(TAG, "Deleting " + dir);

        // TODO: notification if necessary

        if(isOpen) {
            if(!close()) {
                throw new CouchbaseLiteException("The database was open, and could not be closed", Status.INTERNAL_SERVER_ERROR);
            }
        }

        // Wait for all threads to close this database file:
        manager.forgetDatabase(this);
        if(!exists()) {
            return;
        }

        if(!deleteDatabaseFilesAtPath(dir)){
            throw new CouchbaseLiteException("Was not able to delete the database file", Status.INTERNAL_SERVER_ERROR);
        }

        // TODO: in deleteDatabase for iOS does not delete Attachment. deleting attachment should be different method??
        File attachmentsFile = new File(getAttachmentStorePath());
        //recursively delete attachments path
        boolean deleteAttachmentStatus = FileDirUtils.deleteRecursive(attachmentsFile);
        //recursively delete path where attachments stored( see getAttachmentStorePath())
        int lastDotPosition = dir.lastIndexOf('.');
        if( lastDotPosition > 0 ) {
            File attachmentsFileUpFolder = new File(dir.substring(0, lastDotPosition));
            FileDirUtils.deleteRecursive(attachmentsFileUpFolder);
        }
        if (!deleteAttachmentStatus) {
            throw new CouchbaseLiteException("Was not able to delete the attachments files", Status.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * CBLDatabase.m
     * - (BOOL) close: (NSError**)outError
     */
    @InterfaceAudience.Public
    public boolean close(){
        // NOTE: replications are closed in _close()
        this._close();
        return true;
    }

    /**
     * Runs the block within a transaction. If the block returns NO, the transaction is rolled back.
     * Use this when performing bulk write operations like multiple inserts/updates;
     * it saves the overhead of multiple SQLite commits, greatly improving performance.
     *
     * Does not commit the transaction if the code throws an Exception.
     *
     * in CBLDatabase.m
     * - (BOOL) inTransaction: (BOOL(^)(void))block
     */
    @InterfaceAudience.Public
    public boolean runInTransaction(TransactionalTask transactionalTask) {
        return _runInTransaction(transactionalTask);
    }

    /**
     * static NSString* makeLocalDocID(NSString* docID)
     */
    @InterfaceAudience.Public
    static String makeLocalDocID(String documentId) {
        return String.format("_local/%s", documentId);
    }

    //================================================================================
    // CBLDatabase+Internal (Database/CBLDatabase+Internal.m)
    //================================================================================

    /**
     * Backward compatibility
     */
    @InterfaceAudience.Private
    public DatabaseCBForest(String path, Manager manager) {
        this(path, null, manager, false);
    }

    /**
     * in CBLDatabase+Internal.m
     * - (instancetype) _initWithDir: (NSString*)dirPath
     *                          name: (NSString*)name
     *                       manager: (CBLManager*)manager
     *                      readOnly: (BOOL)readOnly
     */
    @InterfaceAudience.Private
    public DatabaseCBForest(String dirPath, String name, Manager manager, boolean readOnly) {
        assert(new File(dirPath).isAbsolute()); //path must be absolute
        this.dir = dirPath;
        if(name == null || name.isEmpty())
            this.name = FileDirUtils.getDatabaseNameFromPath(dirPath);
        else
            this.name = name;
        this.manager = manager;
        this.readOnly = readOnly;
        this.changeListeners = new CopyOnWriteArrayList<ChangeListener>();
        this.docCache = new Cache<String, Document>();
        this.startTime = System.currentTimeMillis();
        this.changesToNotify = new ArrayList<DocumentChange>();
        this.activeReplicators =  Collections.newSetFromMap(new ConcurrentHashMap());
        this.allReplicators = Collections.newSetFromMap(new ConcurrentHashMap());
    }

    /**
     * CBLDatabase+Internal.m
     * - (CBL_BlobStore*) attachmentStore
     */
    @InterfaceAudience.Private
    public BlobStore getAttachments() {
        return attachments;
    }

    /**
     * CBLDatabase+Internal.m
     * - (UInt64) totalDataSize
     */
    @InterfaceAudience.Private
    public long totalDataSize() {
        File f = new File(dir);
        long size = f.length() + attachments.totalDataSize();
        return size;
    }

    /**
     * CBLDatabase+Internal.m
     * - (void) _close
     */
    @InterfaceAudience.Private
    public void _close() {
        if(!isOpen) {
            return;
        }

        Log.w(TAG, "Closing <" + toString() + "> " + dir);

        // TODO: send any notifications if necessary!

        // notify view to close
        if(views != null) {
            for (View view : views.values()) {
                view.databaseClosing();
            }
        }
        views = null;

        // close replicators
        if(activeReplicators != null) {
            for(Replication replicator : activeReplicators) {
                replicator.databaseClosing();
            }
            activeReplicators = null;
        }
        allReplicators = null;

        // close database
        if(forest != null) {
            forest.delete(); // <- release instance. not delete database
            forest = null;
        }

        // close local docs
        closeLocalDocs();

        isOpen = false;
        transactionLevel = 0;

        // clear document cache
        clearDocumentCache();

        // remove this db from manager
        manager.forgetDatabase(this);
    }

    /**
     * CBLDatabase+Internal.m
     * - (BOOL) exists
     */
    @InterfaceAudience.Private
    public boolean exists() {
        return new File(dir).exists();
    }
    /**
     * CBLDatabase+Internal.m
     * - (NSString*) privateUUID
     */
    @InterfaceAudience.Private
    public String privateUUID() {
        return getInfo("privateUUID");
    }

    /**
     * CBLDatabase+Internal.m
     * - (NSString*) publicUUID
     */
    @InterfaceAudience.Private
    public String publicUUID() {
        return getInfo("publicUUID");
    }


    //pragma mark - TRANSACTIONS & NOTIFICATIONS:

    /**
     * Runs the block within a transaction. If the block returns NO, the transaction is rolled back.
     * Use this when performing bulk write operations like multiple inserts/updates;
     * it saves the overhead of multiple SQLite commits, greatly improving performance.
     *
     * Does not commit the transaction if the code throws an Exception.
     *
     * in CBLDatabase+Internal.m
     * - (CBLStatus) _inTransaction: (CBLStatus(^)())block
     */
    @InterfaceAudience.Private
    public boolean _runInTransaction(TransactionalTask transactionalTask) {
        beginTransaction();
        boolean shouldCommit = true;
        try {
            shouldCommit = transactionalTask.run();
        } catch (Exception e) {
            shouldCommit = false;
            Log.e(Database.TAG, e.toString(), e);
            throw new RuntimeException(e);
        } finally {
            endTransaction(shouldCommit);
        }
        return shouldCommit;
    }

    @InterfaceAudience.Private
    public boolean beginTransaction() {
        if(++transactionLevel == 1)
            forestTransaction = new Transaction(forest);
        Log.w(TAG, "%s Begin transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
        return true;
    }

    @InterfaceAudience.Private
    public boolean endTransaction(boolean commit) {

        assert(transactionLevel > 0);
        if(commit) {
            Log.i(TAG, "%s Committing transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
            // ~Transaction() -> db.endTransaction() -> fdb_end_transaction
        }
        else {
            Log.i(TAG, "%s CANCEL transaction (level %d)", Thread.currentThread().getName(), transactionLevel);
            // set state -> abort
            forestTransaction.abort();
            // ~Transaction() -> db.endTransaction() -> fdb_abort_transaction
        }
        if(--transactionLevel == 0){
            forestTransaction.delete();
            forestTransaction = null;
            this.postChangeNotifications();
        }
        return true;
    }


    /**
     * in CBLDatabase+Internal.m
     * - (BOOL) runFilter: (CBLFilterBlock)filter
     *             params: (NSDictionary*)filterParams
     *         onRevision: (CBL_Revision*)rev
     */
    @InterfaceAudience.Private
    public boolean runFilter(ReplicationFilter filter, Map<String, Object> filterParams, RevisionInternal rev) {
        if (filter == null) {
            return true;
        }
        SavedRevision publicRev = new SavedRevision(this, rev);
        return filter.filter(publicRev, filterParams);
    }


    /**
     * in CBLDatabase+Internal.m
     * - (NSString*) description
     */
    @InterfaceAudience.Private
    public String toString(){
        return DatabaseCBForest.class.getName() +"["+ getName() + "]";
    }

    /**
     * in CBLDatabase+Internal.m
     * - (NSUInteger) _documentCount
     */
    @InterfaceAudience.Private
    public int getDocumentCount() {
        DocEnumerator.Options ops = DocEnumerator.Options.getDef();
        ops.setContentOption(ContentOptions.kMetaOnly);
        int count = 0;
        DocEnumerator itr = new DocEnumerator(forest, Slice.getNull(), Slice.getNull(), ops);
        if(itr.doc() != null) {
            do {
                VersionedDocument vdoc = new VersionedDocument(forest, itr.doc());
                if (!vdoc.isDeleted())
                    count++;
            } while (itr.next());
        }
        return count;
    }

    /**
     * in CBLDatabase+Internal.m
     * - (SequenceNumber) _lastSequence
     */
    @InterfaceAudience.Private
    public long getLastSequenceNumber() {
        return forest.getLastSequence().longValue();
    }


    /**
     * in CBLDatabase+Internal.m
     * + (BOOL) deleteDatabaseFilesAtPath: (NSString*)dbDir error: (NSError**)outError
     */
    @InterfaceAudience.Private
    public static boolean deleteDatabaseFilesAtPath(String dbDir){
        File file = new File(dbDir);
        if(file.exists()){
            FileDirUtils.deleteRecursive(file);
        }
        return true;
    }

    /**
     * in CBLDatabase+Internal.m
     * - (CBL_Revision*) getDocumentWithID: (NSString*)docID
     *                          revisionID: (NSString*)inRevID
     *                             options: (CBLContentOptions)options
     *                              status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public RevisionInternal getDocumentWithIDAndRev(String docID, String inRevID, EnumSet<TDContentOptions> options) {
        RevisionInternal result = null;

        // TODO: add VersionDocument(Database, String)
        VersionedDocument doc = new VersionedDocument(forest, new Slice(docID.getBytes()));
        if(!doc.exists()) {
            //throw new CouchbaseLiteException(Status.NOT_FOUND);
            return null;
        }

        String revID = inRevID;
        if(revID == null){
            com.couchbase.lite.cbforest.Revision rev = doc.currentRevision();
            if(rev == null || rev.isDeleted()) {
                //throw new CouchbaseLiteException(Status.DELETED);
                return null;
            }
            // TODO: add String getRevID()
            // TODO: revID is something wrong!!!!!
            //revID = rev.getRevID().getBuf();
            revID =  new String(rev.getRevID().expanded().getBuf());
            Log.w(TAG, "[getDocumentWithIDAndRev()] revID => " + revID);
        }

        result = ForestBridge.revisionObjectFromForestDoc(doc, revID, options);
        if(result == null)
            //throw new CouchbaseLiteException(Status.NOT_FOUND);
            return null;
        // TODO: Attachment support

        // TODO: need to release document?

        return result;
    }
    /**
     * CBLDatabase+Internal.m
     * - (CBL_RevisionList*) getAllRevisionsOfDocumentID: (NSString*)docID
     *                                       onlyCurrent: (BOOL)onlyCurrent
     */
    @InterfaceAudience.Private
    public RevisionList getAllRevisionsOfDocumentID(String docId, boolean onlyCurrent) {
        // TODO: add VersionDocument(KeyStore, String)
        VersionedDocument doc = new VersionedDocument(forest, new Slice(docId.getBytes()));
        if(!doc.exists()) {
            // release
            doc.delete();
            // TODO: or should throw NOT_FOUND exception
            return null;
        }

        RevisionList revs = new RevisionList();

        com.couchbase.lite.cbforest.VectorRevision revNodes = null;
        if(onlyCurrent){
            revNodes = doc.currentRevisions();
        }
        else{
            revNodes = doc.allRevisions();
        }

        for(int i = 0; i < revNodes.size(); i++){
            com.couchbase.lite.cbforest.Revision revNode = revNodes.get(i);
            RevisionInternal rev = new RevisionInternal(docId, new String(revNode.getRevID().getBuf()), revNode.isDeleted());
            // TODO: not sure if sequence is required?
            rev.setSequence(revNode.getSequence().longValue());
            revs.add(rev);
        }

        // release doc
        doc.delete();

        return revs;
    }

    /**
     * Returns the revision history as a _revisions dictionary, as returned by the REST API's ?revs=true option.
     *
     * in CBLDatabase+Internal.m
     * - (NSDictionary*) getRevisionHistoryDict: (CBL_Revision*)rev
     *                        startingFromAnyOf: (NSArray*)ancestorRevIDs
     */
    @InterfaceAudience.Private
    public Map<String, Object> getRevisionHistoryDictStartingFromAnyAncestor(RevisionInternal rev, List<String> ancestorRevIDs) {
        String docId = rev.getDocId();
        String revId = rev.getRevId();
        VersionedDocument doc = new VersionedDocument(forest, new Slice(docId.getBytes()));
        com.couchbase.lite.cbforest.Revision revision = doc.get(new RevIDBuffer(new Slice(revId.getBytes())));
        Map<String, Object> history = ForestBridge.getRevisionHistoryDictStartingFromAnyAncestor(docId, revision, ancestorRevIDs);
        doc.delete();
        return history;
    }

    /**
     * in CBLDatabase+Internal.m
     * - (CBL_RevisionList*) changesSinceSequence: (SequenceNumber)lastSequence
     *                                    options: (const CBLChangesOptions*)options
     *                                     filter: (CBLFilterBlock)filter
     *                                     params: (NSDictionary*)filterParams
     *                                     status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public RevisionList changesSince(long lastSeq, ChangesOptions options, ReplicationFilter filter, Map<String, Object> filterParams) {

        Log.w(TAG, "[changesSince]");

        // http://wiki.apache.org/couchdb/HTTP_database_API#Changes
        // Translate options to ForestDB:
        if(options == null) {
            options = new ChangesOptions();
        }
        DocEnumerator.Options forestOPts = DocEnumerator.Options.getDef();
        forestOPts.setLimit(options.getLimit());
        forestOPts.setInclusiveEnd(true);
        forestOPts.setIncludeDeleted(false);
        boolean includeDocs = (options.isIncludeDocs() || options.isIncludeConflicts() || filter != null);
        if(!includeDocs) {
            forestOPts.setContentOption(ContentOptions.kMetaOnly);
        }
        EnumSet<TDContentOptions> contentOptions = EnumSet.noneOf(TDContentOptions.class);
        contentOptions.add(TDContentOptions.TDNoBody);
        if(includeDocs||filter != null)
            contentOptions = options.getContentOptions();

        RevisionList changes = new RevisionList();
        // TODO: DocEnumerator -> use long instead of BigInteger
        DocEnumerator itr = new DocEnumerator(forest, BigInteger.valueOf(lastSeq), BigInteger.valueOf(Long.MAX_VALUE), forestOPts);
        do {
            VersionedDocument doc = new VersionedDocument(forest, itr.doc());
            List<String> revIDs = null;
            if(options.isIncludeConflicts()) {
                revIDs = ForestBridge.getCurrentRevisionIDs(doc);
            }
            else {
                revIDs = new ArrayList<String>();
                revIDs.add(new String(doc.getRevID().getBuf()));
            }

            for(String revID : revIDs){
                Log.w(TAG, "[changesSince()] revID => " + revID);
                RevisionInternal rev = ForestBridge.revisionObjectFromForestDoc(doc, revID, contentOptions);
                if (runFilter(filter, filterParams, rev)) {
                    changes.add(rev);
                }
            }

        }while(itr.next());
        return changes;
    }


    /**
     * in CBLDatabase+Internal.m
     * - (BOOL) existsDocumentWithID: (NSString*)docID revisionID: (NSString*)revID
     */
    public boolean existsDocumentWithIDAndRev(String docId, String revId) {
        return getDocumentWithIDAndRev(docId, revId, EnumSet.of(TDContentOptions.TDNoBody)) != null;
    }

    /**
     * in CBLDatabase+Internal.m
     * - (CBLStatus) loadRevisionBody: (CBL_MutableRevision*)rev
     *                        options: (CBLContentOptions)options
     */
    @InterfaceAudience.Private
    public RevisionInternal loadRevisionBody(RevisionInternal rev,
                                             EnumSet<TDContentOptions> options)
            throws CouchbaseLiteException {

        // First check for no-op -- if we just need the default properties and already have them:
        if(rev.getBody() != null && options == EnumSet.noneOf(Database.TDContentOptions.class) && rev.getSequence() != 0) {
            return rev;
        }

        if((rev.getDocId() == null) || (rev.getRevId() == null)) {
            Log.e(Database.TAG, "Error loading revision body");
            throw new CouchbaseLiteException(Status.PRECONDITION_FAILED);
        }

        VersionedDocument doc = new VersionedDocument(forest, new Slice(rev.getDocId().getBytes()));
        if(doc == null||!doc.exists())
            throw new CouchbaseLiteException(Status.NOT_FOUND);
        if (!ForestBridge.loadBodyOfRevisionObject(rev, options, doc)) {
            throw new CouchbaseLiteException(Status.NOT_FOUND);
        }
        if(options.contains(TDContentOptions.TDIncludeAttachments)){
            expandAttachmentsIn(rev, options);
        }

        return rev;
    }

    // pragma mark - HISTORY:

    //================================================================================
    // CBLDatabase+Attachments (Database/CBLDatabase+Attachments.m)
    //================================================================================



    /**
     * in CBLDatabase+Attachments.m
     *  (NSString*) attachmentStorePath
     */
    @InterfaceAudience.Private
    public String getAttachmentStorePath() {
        String attachmentStorePath = dir;
        int lastDotPosition = attachmentStorePath.lastIndexOf('.');
        if( lastDotPosition > 0 ) {
            attachmentStorePath = attachmentStorePath.substring(0, lastDotPosition);
        }
        attachmentStorePath = attachmentStorePath + File.separator + "attachments";
        return attachmentStorePath;
    }

    // pragma mark - ATTACHMENT WRITERS:

    /**
     * CBLDatabase+Attachments.m
     * - (CBL_BlobStoreWriter*) attachmentWriter
     */
    @InterfaceAudience.Private
    public BlobStoreWriter getAttachmentWriter() {
        return new BlobStoreWriter(getAttachments());
    }

    /**
     * lazy loading
     */
    @InterfaceAudience.Private
    private Map<String, BlobStoreWriter> getPendingAttachmentsByDigest() {
        if (pendingAttachmentsByDigest == null) {
            pendingAttachmentsByDigest = new HashMap<String, BlobStoreWriter>();
        }
        return pendingAttachmentsByDigest;
    }

    /**
     * CBLDatabase+Attachments.m
     * - (void) rememberAttachmentWriter: (CBL_BlobStoreWriter*)writer
     */
    @InterfaceAudience.Private
    public void rememberAttachmentWriter(BlobStoreWriter writer) {
        getPendingAttachmentsByDigest().put(writer.mD5DigestString(), writer);
    }

    /**
     * CBLDatabase+Attachments.m
     * - (void) rememberAttachmentWritersForDigests: (NSDictionary*)blobsByDigests
     */
    @InterfaceAudience.Private
    public void rememberAttachmentWritersForDigests(Map<String, BlobStoreWriter> blobsByDigest) {
        getPendingAttachmentsByDigest().putAll(blobsByDigest);
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Private
    void insertAttachmentForSequence(AttachmentInternal attachment, long sequence) throws CouchbaseLiteException {
        throw new CouchbaseLiteException(Status.METHOD_NOT_ALLOWED);
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Private
    public void insertAttachmentForSequenceWithNameAndType(InputStream contentStream, long sequence, String name, String contentType, int revpos) throws CouchbaseLiteException {
        throw new CouchbaseLiteException(Status.METHOD_NOT_ALLOWED);
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Private
    public void insertAttachmentForSequenceWithNameAndType(long sequence, String name, String contentType, int revpos, BlobKey key) throws CouchbaseLiteException {
        throw new CouchbaseLiteException(Status.METHOD_NOT_ALLOWED);
    }

    /**
     * Given a decoded attachment with a "follows" property, find the associated CBL_BlobStoreWriter
     * and install it into the blob-store.
     *
     * in CBLDatabase+Attachments.m
     * - (CBLStatus) installAttachment: (CBL_Attachment*)attachment
     */
    @InterfaceAudience.Private
    public void installAttachment(AttachmentInternal attachment, Map<String, Object> attachInfo) throws CouchbaseLiteException {
        String digest = (String) attachInfo.get("digest");
        if (digest == null) {
            throw new CouchbaseLiteException(Status.BAD_ATTACHMENT);
        }
        if (pendingAttachmentsByDigest != null && pendingAttachmentsByDigest.containsKey(digest)) {
            BlobStoreWriter writer = pendingAttachmentsByDigest.get(digest);
            try {
                writer.install();
                attachment.setBlobKey(writer.getBlobKey());
                attachment.setLength(writer.getLength());
            } catch (Exception e) {
                throw new CouchbaseLiteException(e, Status.STATUS_ATTACHMENT_ERROR);
            }
        }
    }


    // pragma mark - LOOKING UP ATTACHMENTS:

    /**
     * * in CBLDatabase+Attachments.m
     * - (NSDictionary*) attachmentsForDocID: (NSString*)docID
     *                                 revID: (NSString*)revID
     *                                status: (CBLStatus*)outStatus
     */
    private Map getAttachments(String docID, String revID){
        RevisionInternal mrev = new RevisionInternal(docID, revID, false);
        try {
            mrev = loadRevisionBody(mrev, EnumSet.noneOf(TDContentOptions.class));
        } catch (CouchbaseLiteException e) {
            //Log.w(TAG, e.getMessage());
            return null;
        }
        return mrev.getAttachments();
    }

    // pragma mark - UPDATING _attachments DICTS:

    /**
     * in CBLDatabase+Attachments.m
     * - (void) expandAttachmentsIn: (CBL_MutableRevision*)rev options: (CBLContentOptions)options
     */
    private void expandAttachmentsIn(final RevisionInternal rev, EnumSet<TDContentOptions> options){
        final boolean decodeAttachments = !options.contains(TDContentOptions.TDLeaveAttachmentsEncoded);
        rev.mutateAttachments(new CollectionUtils.Functor<Map<String,Object>,Map<String,Object>>() {
            public Map<String, Object> invoke(Map<String, Object> attachment) {
                String encoding = (String)attachment.get("encoding");
                boolean decodeIt = decodeAttachments && (encoding != null);
                if(decodeIt || attachment.get("stub") != null || attachment.get("follows") != null){
                    Map<String, Object> expanded = new HashMap<String, Object>(attachment);
                    expanded.remove("stub");
                    expanded.remove("follows");

                    String base64Data = (String)attachment.get("data");
                    if(base64Data == null || decodeIt){
                        byte[] data = null;
                        if(base64Data!=null)
                            try {
                                data = Base64.decode(base64Data);
                            } catch (IOException e) {
                                Log.w(TAG, "Unable to decode");
                                data = null;
                            }
                        else
                            data = dataForAttachmentDict(attachment);
                        if(data == null) {
                            Log.w(TAG, "Can't get binary data of attachment '" + name + "' of " + rev);
                            return attachment;
                        }
                        if(decodeIt){
                            // TODO: revisit later!!!!
                            try {
                                data = Manager.getObjectMapper().readValue(data, byte[].class);
                            } catch (IOException e) {
                                Log.w(TAG, "parse error");
                                data = null;
                            }
                            if(data == null){
                                Log.w(TAG, "Can't unzip attachment '" + name + "' of " + rev);
                                return attachment;
                            }
                            expanded.remove("encoding");
                            expanded.remove("encoded_length");
                        }
                        expanded.put("data", Base64.encodeBytes(data));
                    }
                    attachment = expanded;
                }
                return attachment;
            }
        });
    }

    /**
     * in CBLDatabase+Attachments.m
     * - (NSData*) dataForAttachmentDict: (NSDictionary*)attachmentDict
     */
    private byte[] dataForAttachmentDict(Map<String,Object> attachmentDict){
        URL fileURL = fileForAttachmentDict(attachmentDict);
        if(fileURL == null)
            return null;

        byte[] fileData = null;
        try {
            InputStream is = fileURL.openStream();
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            StreamUtils.copyStream(is, os);
            fileData = os.toByteArray();
        } catch (IOException e) {
            Log.e(Log.TAG_SYNC,"could not retrieve attachment data: %S",e);
            return null;
        }
        return fileData;
    }

    /**
     * in CBLDatabase+Attachments.m
     * - (NSURL*) fileForAttachmentDict: (NSDictionary*)attachmentDict
     */
    @InterfaceAudience.Private
    public URL fileForAttachmentDict(Map<String,Object> attachmentDict) {
        String digest = (String)attachmentDict.get("digest");
        if (digest == null) {
            return null;
        }
        String path = null;
        Object pending = pendingAttachmentsByDigest.get(digest);
        if (pending != null) {
            if (pending instanceof BlobStoreWriter) {
                path = ((BlobStoreWriter) pending).getFilePath();
            } else {
                BlobKey key = new BlobKey((byte[])pending);
                path = attachments.pathForKey(key);
            }
        } else {
            // If it's an installed attachment, ask the blob-store for it:
            BlobKey key = new BlobKey(digest);
            path = attachments.pathForKey(key);
        }

        URL retval = null;
        try {
            retval = new File(path).toURI().toURL();
        } catch (MalformedURLException e) {
            //NOOP: retval will be null
        }
        return retval;
    }

    /**
     * backward compatibility
     */
    @InterfaceAudience.Private
    public void processAttachmentsForRevision(Map<String, AttachmentInternal> attachments, RevisionInternal rev, long parentSequence) throws CouchbaseLiteException {
        throw new CouchbaseLiteException(Status.METHOD_NOT_ALLOWED);
    }

    /**
     * Given a revision, updates its _attachments dictionary for storage in the database.
     *
     * in CBLDatabase+Attachments.m
     * - (BOOL) processAttachmentsForRevision: (CBL_MutableRevision*)rev
     *                              prevRevID: (NSString*)prevRevID
     *                                 status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public boolean processAttachmentsForRevision(final RevisionInternal rev, final String prevRevID) throws CouchbaseLiteException{
        Status outStatus = new Status(Status.OK);

        final Map<String, Object> attachments = rev.getAttachments();
        if(attachments == null)
            return true; // no-op: no attachments

        // Deletions can't have attachments:
        if(rev.isDeleted() || attachments.size() == 0){
            Map<String, Object> body = rev.getProperties();
            body.remove("_attachments");
            rev.setProperties(body);
            return true;
        }

        final int generation = RevisionInternal.generationFromRevID(prevRevID) + 1;
        // initialize here because unable to initialize in inner class
        //final Map<String, Map<String, Object>> parentAttachments = null;
        final Map<String, Map<String, Object>> parentAttachments = getAttachments(rev.getDocId(), prevRevID);;

        return rev.mutateAttachments(new CollectionUtils.Functor<Map<String,Object>,Map<String,Object>>() {

            public Map<String, Object> invoke(Map<String, Object> attachInfo) {
                try {
                    String name = (String)attachInfo.get("name");
                    AttachmentInternal attachment = new AttachmentInternal(name, attachInfo);

                    if(attachment == null){
                        return null;
                    }
                    else if(attachment.getData()!=null){
                        // If there's inline attachment data, decode and store it:
                        BlobKey outBlobKey = new BlobKey();
                        boolean storedBlob = getAttachments().storeBlob(attachment.getData(), outBlobKey);
                        attachment.setBlobKey(outBlobKey);
                        if (!storedBlob) {
                            throw new CouchbaseLiteException(Status.STATUS_ATTACHMENT_ERROR);
                        }
                    }
                    else if (attachInfo.containsKey("follows") &&
                            ((Boolean)attachInfo.get("follows")).booleanValue()) {
                        // "follows" means the uploader provided the attachment in a separate MIME part.
                        // This means it's already been registered in _pendingAttachmentsByDigest;
                        // I just need to look it up by its "digest" property and install it into the store:
                        installAttachment(attachment, attachInfo);
                    }
                    else if (attachInfo.containsKey("stub") &&
                            ((Boolean)attachInfo.get("stub")).booleanValue()) {
                        // "stub" on an incoming revision means the attachment is the same as in the parent.
                        if(parentAttachments == null && prevRevID != null && prevRevID.length() > 0){
                            //parentAttachments = getAttachments(rev.getDocId(), prevRevID);
                            if(parentAttachments == null){
                                throw new CouchbaseLiteException(Status.BAD_ATTACHMENT);
                            }
                        }
                        Map<String, Object> parentAttachment = parentAttachments.get(name);
                        if(parentAttachment==null)
                            return null;
                        return parentAttachment;
                    }

                    // Set or validate the revpos:
                    if(attachment.getRevpos() == 0) {
                        attachment.setRevpos(generation);
                    }
                    else if(attachment.getRevpos() >= generation) {
                        return null;
                    }
                    return attachment.asStubDictionary();
                } catch (CouchbaseLiteException e) {
                    e.printStackTrace();
                    return null;
                }
            }
        });
    }


    // pragma mark - MISC.:

    /**
     *  Replaces or removes a single attachment in a document, by saving a new revision whose only
     *  change is the value of the attachment.
     *
     * in CBLDatabase+Attachments.m
     * - (CBL_Revision*) updateAttachment: (NSString*)filename
     *                               body: (CBL_BlobStoreWriter*)body
     *                               type: (NSString*)contentType
     *                           encoding: (CBLAttachmentEncoding)encoding
     *                            ofDocID: (NSString*)docID
     *                              revID: (NSString*)oldRevID
     *                             status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public RevisionInternal updateAttachment(String filename, BlobStoreWriter body, String contentType, AttachmentInternal.AttachmentEncoding encoding, String docID, String oldRevID) throws CouchbaseLiteException {

        if(filename == null || filename.length() == 0 || (body != null && contentType == null) || (oldRevID != null && docID == null) || (body != null && docID == null)) {
            throw new CouchbaseLiteException(Status.BAD_REQUEST);
        }

        RevisionInternal oldRev = new RevisionInternal(docID, oldRevID, false);

        if(oldRevID != null) {
            // Load existing revision if this is a replacement:
            try {
                loadRevisionBody(oldRev, EnumSet.noneOf(TDContentOptions.class));
            } catch (CouchbaseLiteException e) {
                if (e.getCBLStatus().getCode() == Status.NOT_FOUND && existsDocumentWithIDAndRev(docID, null) ) {
                    throw new CouchbaseLiteException(Status.CONFLICT);
                }
            }
        } else {
            // If this creates a new doc, it needs a body:
            oldRev.setBody(new Body(new HashMap<String,Object>()));
        }

        // Update the _attachments dictionary:
        Map<String,Object> attachments = oldRev.getAttachments();
        if (attachments == null)
            attachments = new HashMap<String, Object>();
        if (body != null) {
            BlobKey key = body.getBlobKey();
            String digest = key.base64Digest();

            Map<String, BlobStoreWriter> blobsByDigest = new HashMap<String, BlobStoreWriter>();
            blobsByDigest.put(digest,body);
            rememberAttachmentWritersForDigests(blobsByDigest);

            String encodingName = (encoding == AttachmentInternal.AttachmentEncoding.AttachmentEncodingGZIP) ? "gzip" : null;
            Map<String,Object> dict = new HashMap<String, Object>();
            dict.put("digest", digest);
            dict.put("length", body.getLength());
            dict.put("follows", true);
            dict.put("content_type", contentType);
            dict.put("encoding", encodingName);
            attachments.put(filename, dict);
        }
        else{
            if (oldRevID != null && !attachments.containsKey(filename) ) {
                throw new CouchbaseLiteException(Status.NOT_FOUND);
            }
            attachments.remove(filename);
        }

        Map<String, Object> properties = oldRev.getProperties();
        properties.put("_attachments",attachments);
        oldRev.setProperties(properties);

        // Store a new revision with the updated _attachments:
        Status putStatus = new Status();
        RevisionInternal newRev = putRevision(oldRev, oldRevID, false, putStatus);
        if(body == null && putStatus.getCode() == Status.CREATED)
            putStatus.setCode(Status.OK);

        return newRev;
    }


    //================================================================================
    // CBLDatabase+Insertion (Database/CBLDatabase+Insertion.m)
    //================================================================================

    // pragma mark - INSERTION:

    // Backward compatibility
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, Status resultStatus) throws CouchbaseLiteException {
        return putRevision(rev, prevRevId, false, resultStatus);
    }

    // Backward compatibility
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, boolean allowConflict) throws CouchbaseLiteException {
        Status ignoredStatus = new Status();
        return putRevision(rev, prevRevId, allowConflict, ignoredStatus);
    }

    /**
     * in CBLDatabase+Insertion.m  -
     * - (CBL_Revision*) putRevision: (CBL_MutableRevision*)putRev
     *                prevRevisionID: (NSString*)inPrevRevID
     *                 allowConflict: (BOOL)allowConflict
     *                        status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal putRev, String inPrevRevID, boolean allowConflict, Status outStatus) throws CouchbaseLiteException{
        return putDoc(putRev.getDocId(), putRev.getProperties(), inPrevRevID, allowConflict, outStatus);
    }

    /**
     * in CBLDatabase+Insertion.m  -
     * (CBL_Revision*)  putDocID: (NSString*)inDocID
     *                  properties: (NSMutableDictionary*)properties
     *                  prevRevisionID: (NSString*)inPrevRevID
     *                  allowConflict: (BOOL)allowConflict
     *                  status: (CBLStatus*)outStatus
     */
    @InterfaceAudience.Private
    public RevisionInternal putDoc(String inDocID, Map<String, Object> properties, String inPrevRevID, boolean allowConflict, Status resultStatus) throws CouchbaseLiteException {


        String docID = inDocID;
        String prevRevID = inPrevRevID;
        boolean deleting = false;
        if(properties == null || (properties.get("_deleted") != null && properties.get("_deleted") == Boolean.TRUE)){
            deleting = true;
        }

        Log.w(TAG, "[putDoc()] _id="+docID+", _rev="+prevRevID+", _deleted=" + deleting + ", allowConflict=" + allowConflict);

        if( (prevRevID != null && docID == null) ||
                (deleting && docID == null) ||
                (docID != null && !DatabaseUtil.isValidDocumentId(docID))){
            throw new CouchbaseLiteException(Status.BAD_REQUEST);
        }

        if(forest.isReadOnly()){
            throw new CouchbaseLiteException(Status.FORBIDDEN);
        }

        RevisionInternal putRev = null;
        DocumentChange change = null;


        byte[] json = null;
        if(properties != null){
            if(properties.containsKey("_attachments")){
                // Add any new attachment data to the blob-store, and turn all of them into stubs:
                //FIX: Optimize this to avoid creating a revision object
                RevisionInternal tmpRev = new RevisionInternal(docID, prevRevID, deleting);
                tmpRev.setProperties(properties);
                if(!processAttachmentsForRevision(tmpRev, prevRevID))
                    return null;
                properties = new HashMap<String, Object>(tmpRev.getProperties());
            }

            // TODO: json = [CBL_Revision asCanonicalJSON: properties error: NULL];
            try {
                json = Manager.getObjectMapper().writeValueAsBytes(properties);
                if(json == null || json.length == 0)
                    throw new CouchbaseLiteException(Status.BAD_JSON);
            } catch (Exception e) {
                throw new CouchbaseLiteException(Status.BAD_JSON);
            }
        }
        else{
            json = "{}".getBytes();
        }


        //Log.w(TAG, "[putDoc()] json => " + new String(json));

        beginTransaction();
        try{
            com.couchbase.lite.cbforest.Document rawDoc = new com.couchbase.lite.cbforest.Document();
            if(docID != null && !docID.isEmpty()){
                // Read the doc from the database:
                rawDoc.setKey(new Slice(docID.getBytes()));
                forest.read(rawDoc);
            }
            else{
                // Create new doc ID, and don't bother to read it since it's a new doc:
                docID = Misc.TDCreateUUID();
                rawDoc.setKey(new Slice(docID.getBytes()));
            }

            // Parse the document revision tree:
            VersionedDocument doc = new VersionedDocument(forest, rawDoc);
            com.couchbase.lite.cbforest.Revision revNode;

            if(inPrevRevID != null){
                // Updating an existing revision; make sure it exists and is a leaf:
                // TODO -> add VersionDocument.get(String revID)
                //      -> or Efficiently pass RevID to VersionDocument.get(RevID)
                //revNode = doc.get(new RevID(inPrevRevID));
                Log.w(TAG, "[putDoc()] inPrevRevID => " + inPrevRevID);
                revNode = doc.get(new RevIDBuffer(new Slice(inPrevRevID.getBytes())));
                if(revNode == null)
                    throw new CouchbaseLiteException(Status.NOT_FOUND);
                else if(!allowConflict && !revNode.isLeaf())
                    throw new CouchbaseLiteException(Status.CONFLICT);
            }
            else{
                // No parent revision given:
                if(deleting){
                    // Didn't specify a revision to delete: NotFound or a Conflict, depending
                    if (doc.exists())
                        throw new CouchbaseLiteException(Status.CONFLICT);
                    else
                        throw new CouchbaseLiteException(Status.NOT_FOUND);
                }
                // If doc exists, current rev must be in a deleted state or there will be a conflict:
                revNode = doc.currentRevision();
                if(revNode != null){
                    if(revNode.isDeleted()) {
                        // New rev will be child of the tombstone:
                        // (T0D0: Write a horror novel called "Child Of The Tombstone"!)
                        prevRevID = new String(revNode.getRevID().getBuf());
                    }else {
                        throw new CouchbaseLiteException(Status.CONFLICT);
                    }
                }
            }

            boolean hasValidations = validations != null && validations.size() > 0;

            // Compute the new revID:
            String newRevID = generateRevIDForJSON(json, deleting, prevRevID);
            if(newRevID == null)
                throw new CouchbaseLiteException(Status.BAD_ID); // invalid previous revID (no numeric prefix)

            Log.w(TAG, "[putDoc()] newRevID => "+newRevID);

            putRev = new RevisionInternal(docID, newRevID, deleting);

            if(properties!=null){
                properties.put("_id", docID);
                properties.put("_rev", newRevID);
                putRev.setProperties(properties);
            }

            // Run any validation blocks:
            if(hasValidations){
                // Fetch the previous revision and validate the new one against it:
                RevisionInternal fakeNewRev = putRev.copyWithDocID(putRev.getDocId(), null);
                RevisionInternal prevRev = null;
                if(prevRevID != null){
                    prevRev = new RevisionInternal(docID, prevRevID, revNode.isDeleted());
                }
                validateRevision(fakeNewRev, prevRev, prevRevID);
            }

            // Add the revision to the database:
            int status;
            boolean isWinner;
            {
                // TODO - add new RevIDBuffer(String)
                // TODO - add RevTree.insert(String, String, boolean, boolean, RevID arg4, boolean)
                com.couchbase.lite.cbforest.Revision fdbRev = doc.insert(new RevIDBuffer(new Slice(newRevID.getBytes())),
                        new Slice(json),
                        deleting,
                        (putRev.getAttachments() != null),
                        revNode,
                        allowConflict);
                status = doc.getLatestHttpStatus();
                resultStatus.setCode(status);
                if(fdbRev!=null)
                    putRev.setSequence(fdbRev.getSequence().longValue());
                if(fdbRev == null && resultStatus.isError())
                    throw new CouchbaseLiteException(resultStatus);

                // TODO - is address compare good enough??
                if(fdbRev != null)
                    isWinner = fdbRev.isSameAddress(doc.currentRevision());
                else
                    // Revision already exists without error
                    isWinner = false;
            }

            // prune call will invalidate fdbRev ptr, so let it go out of scope

            doc.prune(maxRevTreeDepth);
            doc.save(forestTransaction);

            Log.w(TAG, "[putDoc()] doc.currentRevision().getRevID().getBuf() => " + new String(doc.currentRevision().getRevID().getBuf()));

            // TODO - implement doc.dump()

            // TODO - !!!! change With new Revision !!!!!
            change = changeWithNewRevision(putRev, isWinner, doc, null);

            // Success!
            if(deleting) {
                resultStatus.setCode(Status.OK);
            }
            else {
                resultStatus.setCode(Status.CREATED);
            }
        }
        finally {
            endTransaction(resultStatus.isSuccessful());
        }

        // TODO - status check

        // TODO - logging

        // Epilogue: A change notification is sent:
        if(change != null)
            notifyChange(change);

        Log.w(TAG, "[putDoc()] putRev => " + putRev);
        //Log.w(TAG, "[putDoc()] json => " + new String(json));

        return putRev;
    }

    /**
     * Add an existing revision of a document (probably being pulled) plus its ancestors.
     *
     * in CBLDatabase+Insertion.m
     * - (CBLStatus) forceInsert: (CBL_Revision*)inRev
     *          revisionHistory: (NSArray*)history  // in *reverse* order, starting with rev's revID
     *                  source: (NSURL*)source
     */
    @InterfaceAudience.Private
    public void forceInsert(RevisionInternal inRev, List<String> history, URL source) throws CouchbaseLiteException {


        RevisionInternal rev = inRev.copyWithDocID(inRev.getDocId(), inRev.getRevId());
        rev.setSequence(0);
        String docID = rev.getDocId();
        String revId = rev.getRevId();
        if(!DatabaseUtil.isValidDocumentId(docID) || (revId == null)) {
            throw new CouchbaseLiteException(Status.BAD_ID);
        }

        if(forest.isReadOnly())
            throw new CouchbaseLiteException(Status.FORBIDDEN);

        int historyCount = 0;
        if (history != null) {
            historyCount = history.size();
        }
        if(historyCount == 0) {
            history = new ArrayList<String>();
            history.add(revId);
            historyCount = 1;
        } else if(!history.get(0).equals(rev.getRevId())) {
            throw new CouchbaseLiteException(Status.BAD_ID);
        }

        if(inRev.getAttachments()!=null){
            // TODO - attachments!!!
        }

        byte[] json = encodeDocumentJSON(inRev);
        if(json==null)
            throw new CouchbaseLiteException(Status.BAD_JSON);

        Log.w(TAG, "[forceInsert()] json => " + new String(json));


        DocumentChange change = null;
        Status resultStatus = new Status();

        beginTransaction();
        try {
            // First get the CBForest doc:
            VersionedDocument doc = new VersionedDocument(forest, new Slice(docID.getBytes()));

            // Add the revision & ancestry to the doc:
            VectorRevID historyVector = new VectorRevID();
            convertRevIDs(history, historyVector);
            int common = doc.insertHistory(historyVector, new Slice(json), inRev.isDeleted(), (inRev.getAttachments()!=null));
            Log.w(TAG, "common => " + common);
            if(common < 0) {
                resultStatus.setCode(Status.BAD_REQUEST);
                throw new CouchbaseLiteException(resultStatus); // generation numbers not in descending order
            }
            else if(common == 0) {
                resultStatus.setCode(Status.OK);
                return; // No-op: No new revisions were inserted.
            }

            // Validate against the common ancestor:
            // TODO: NEED to implement validation

            doc.prune(maxRevTreeDepth);
            doc.save(forestTransaction);

            change = changeWithNewRevision(inRev,
                    false, // might be, but not known for sure
                    doc,
                    source);

            // Success!
            resultStatus.setCode(Status.CREATED);

        }finally {
            endTransaction(resultStatus.isSuccessful());
        }

        if(change != null)
            notifyChange(change);

        return;
    }

    /**
     * CBLDatabase+Insertion.m
     * static void convertRevIDs(NSArray* revIDs,
     *                          std::vector<revidBuffer> &historyBuffers,
     *                          std::vector<revid> &historyVector)
     */
    @InterfaceAudience.Private
    private static void convertRevIDs(List<String> history, VectorRevID historyVector){
        for(String revID : history){
            Log.w(TAG, "revID => " + revID);
            //RevID revid = new RevID(revID.getBytes());
            //historyVector.add(revid);
            //TODO add RevIDBuffer(String or byte[])
            RevIDBuffer revidbuffer = new RevIDBuffer(new Slice(revID.getBytes()));
            historyVector.add(revidbuffer);
        }
    }

    // pragma mark - VALIDATION:

    /**
     * in CBLDatabase+Insertion.m
     * - (CBLStatus) validateRevision: (CBL_Revision*)newRev
     *               previousRevision: (CBL_Revision*)oldRev
     *                    parentRevID: (NSString*)parentRevID
     */
    @InterfaceAudience.Private
    public void validateRevision(RevisionInternal newRev,
                                 RevisionInternal oldRev,
                                 String parentRevID)
            throws CouchbaseLiteException {

        if(validations == null || validations.size() == 0) {
            return;
        }

        SavedRevision publicRev = new SavedRevision(this, newRev);
        publicRev.setParentRevisionID(parentRevID);

        ValidationContextImpl context = new ValidationContextImpl(this, oldRev, newRev);

        for (String validationName : validations.keySet()) {
            Validator validation = getValidation(validationName);
            validation.validate(publicRev, context);
            if(context.getRejectMessage() != null) {
                throw new CouchbaseLiteException(context.getRejectMessage(), Status.FORBIDDEN);
            }
        }
    }


    //================================================================================
    // CBLDatabase+Replication (Database/CBLDatabase+Replication.m)
    //================================================================================

    //================================================================================
    // CBLDatabase+LocalDocs (Database/CBLDatabase+LocalDocs.m)
    //================================================================================

    /**
     * CBLDatabase+LocalDocs.m
     * - (Database*) localDocs
     */
    @InterfaceAudience.Private
    private com.couchbase.lite.cbforest.Database getLocalDocs(){
        if(localDocs == null){
            String path = new File(dir, "local.forest").getAbsolutePath();
            Config config = com.couchbase.lite.cbforest.Database.defaultConfig();
            // TODO - update config
            localDocs = new com.couchbase.lite.cbforest.Database(path,
                    OpenFlags.FDB_OPEN_FLAG_CREATE,
                    config);
            Log.w(TAG, toString() + ": Opened _local docs db");
        }
        //closeLocalDocsSoon();
        return localDocs;
    }

    /**
     * CBLDatabase+LocalDocs.m
     * - (void) closeLocalDocs
     */
    @InterfaceAudience.Private
    private void closeLocalDocs(){
        if(localDocs!=null){
            localDocs.delete(); // <- release instance. not delete database
            localDocs = null;
            Log.w(TAG, toString() + ": Closed _local docs db");
        }
    }

    // TODO: need??
    /**
     * CBLDatabase+LocalDocs.m
     * - (void) closeLocalDocsSoon
     */
    @InterfaceAudience.Private
    private void closeLocalDocsSoon(){
        // TODO: need??
    }

    /**
     * CBLDatabase+LocalDocs.m
     * static NSDictionary* getDocProperties(const Document& doc)
     */
    @InterfaceAudience.Private
    public static Map<String, Object> getDocProperties(com.couchbase.lite.cbforest.Document doc){
        if(doc == null)
            return null;
        if(doc.getBody().getBuf()==null)
            return null;
        Log.w(TAG, "doc.getBody() => " + doc.getBody());
        Log.w(TAG, "doc.getBody().getBuf() => " + doc.getBody().getBuf());
        String json = new String(doc.getBody().getBuf());
        try {
            return  Manager.getObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            Log.w(Database.TAG, "Error parsing local doc JSON", e);
            return null;
        }
    }

    /**
     * CBLDatabase+LocalDocs.m
     * - (CBL_Revision*) getLocalDocumentWithID: (NSString*)docID
     *                               revisionID: (NSString*)revID
     */
    @InterfaceAudience.Private
    public RevisionInternal getLocalDocument(String docID, String revID) {
        if(docID == null|| !docID.startsWith("_local/"))
            return null;

        com.couchbase.lite.cbforest.Document doc = getLocalDocs().get(new Slice(docID.getBytes()));
        if(!doc.exists())
            return null;

        String gotRevID = new String(doc.getMeta().getBuf());
        if(revID!=null && !revID.equals(gotRevID))
            return null;

        Map<String,Object> properties = getDocProperties(doc);
        if(properties == null)
            return null;
        properties.put("_id", docID);
        properties.put("_rev", gotRevID);
        RevisionInternal result = new RevisionInternal(docID, gotRevID, false);
        result.setProperties(properties);
        return result;

    }

    /**
     * CBLDatabase+LocalDocs.m
     * - (CBL_Revision*) putLocalRevision: (CBL_Revision*)revision
     *                     prevRevisionID: (NSString*)prevRevID
     *                           obeyMVCC: (BOOL)obeyMVCC
     *                             status: (CBLStatus*)outStatus
     *
     *   Note: Not sure what obeyMVCC is. Not supported it yet.
     */
    @InterfaceAudience.Private
    public RevisionInternal putLocalRevision(RevisionInternal revision, String prevRevID) throws CouchbaseLiteException {
        String docID = revision.getDocId();
        if(!docID.startsWith("_local/")) {
            throw new CouchbaseLiteException(Status.BAD_ID);
        }

        if(revision.isDeleted()) {
            // DELETE:
            deleteLocalDocument(docID, prevRevID);
            return revision;
        }
        else{
            // PUT:
            byte[] json = encodeDocumentJSON(revision);
            Log.w(TAG, "json => " + new String(json));
            Transaction t = new Transaction(getLocalDocs());
            try {
                Slice key = new Slice(docID.getBytes());
                com.couchbase.lite.cbforest.Document doc = getLocalDocs().get(key);
                int generation = 0;
                if (prevRevID != null) {
                    generation = RevisionInternal.generationFromRevID(prevRevID);
                    if (generation == 0) {
                        throw new CouchbaseLiteException(Status.BAD_ID);
                    }
                    if (!prevRevID.equals(new String(doc.getMeta().getBuf())))
                        throw new CouchbaseLiteException(Status.CONFLICT);
                } else {
                    if (doc.exists()) {
                        throw new CouchbaseLiteException(Status.CONFLICT);
                    }
                }
                String newRevID = Integer.toString(++generation) + "-local";
                t.set(key, new Slice(newRevID.getBytes()), new Slice(json));
                return revision.copyWithDocID(docID, newRevID);
            }finally {
                t.delete(); // without close transaction, causes deadlock....
            }
        }
    }

    /**
     * CBLDatabase+LocalDocs.m
     * - (CBLStatus) deleteLocalDocumentWithID: (NSString*)docID
     *                              revisionID: (NSString*)revID
     *                                obeyMVCC: (BOOL)obeyMVCC;
     *
     *  Note: Not sure what obeyMVCC is. Not supported it yet.
     */
    @InterfaceAudience.Private
    public void deleteLocalDocument(String docID, String revID) throws CouchbaseLiteException {
        if(docID == null|| !docID.startsWith("_local/")) {
            throw new CouchbaseLiteException(Status.BAD_ID);
        }

        if(revID == null) {
            // Didn't specify a revision to delete: 404 or a 409, depending
            if (getLocalDocument(docID, null) != null) {
                throw new CouchbaseLiteException(Status.CONFLICT);
            }
            else {
                throw new CouchbaseLiteException(Status.NOT_FOUND);
            }
        }

        Transaction t = new Transaction(getLocalDocs());
        com.couchbase.lite.cbforest.Document doc = getLocalDocs().get(new Slice(docID.getBytes()));
        if(!doc.exists())
            throw new CouchbaseLiteException(Status.NOT_FOUND);
        else if(!revID.equals(new String(doc.getMeta().getBuf())))
            throw new CouchbaseLiteException(Status.CONFLICT);
        else
            t.del(doc);
        t.delete();
    }

    // pragma mark - INFO FOR KEY:

    /**
     * CBLDatabase+LocalDocs.m
     * - (NSString*) infoForKey: (NSString*)key
     */
    @InterfaceAudience.Private
    String getInfo(String key){
        com.couchbase.lite.cbforest.Document doc = getLocalDocs().get(new Slice(key.getBytes()));
        byte[] bytes = doc.getBody().getBuf();
        if(bytes != null)
            return new String(bytes);
        else
            return null;
    }

    /**
     * CBLDatabase+LocalDocs.m
     * - (CBLStatus) setInfo: (NSString*)info forKey: (NSString*)key
     */
    @InterfaceAudience.Private
    Status setInfo(String key, String info){
        Transaction t = new Transaction(getLocalDocs());
        t.set(new Slice(key.getBytes()), new Slice(info.getBytes()));
        t.delete();
        return new Status(Status.OK);
    }

    // TODO: need??
    /**
     * Deletes the local document with the given ID.
     */
    @InterfaceAudience.Public
    public boolean deleteLocalDocument(String id) throws CouchbaseLiteException {
        /*
        id = makeLocalDocumentId(id);
        RevisionInternal prevRev = getLocalDocument(id, null);
        if (prevRev == null) {
            return false;
        }
        deleteLocalDocument(id, prevRev.getRevId());
        return true;
        */
        return false;
    }
    // TODO: need??
    @InterfaceAudience.Public
    public boolean putLocalDocument(String id, Map<String, Object> properties) throws CouchbaseLiteException {
        return false;
    }



}

