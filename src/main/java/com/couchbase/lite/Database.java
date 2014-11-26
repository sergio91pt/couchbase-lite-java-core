/**
 * Original iOS version by  Jens Alfke
 * Ported to Android by Marty Schoch
 *
 * Copyright (c) 2012 Couchbase, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package com.couchbase.lite;


import com.couchbase.lite.internal.AttachmentInternal;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.storage.SQLException;
import com.couchbase.lite.storage.SQLiteStorageEngine;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.PersistentCookieStore;
import com.couchbase.lite.util.Log;

import java.io.InputStream;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A CouchbaseLite database.
 */
public interface Database {
    /**
     * @exclude
     */
    public static final String TAG = Log.TAG;

    /**
     * Length that constitutes a 'big' attachment
     * @exclude
     */
    public static int kBigAttachmentLength = (16*1024);

    /**
     * Options for what metadata to include in document bodies
     * @exclude
     */
    public enum TDContentOptions {
        TDIncludeAttachments,
        TDIncludeConflicts,
        TDIncludeRevs,
        TDIncludeRevsInfo,
        TDIncludeLocalSeq,
        TDNoBody,
        TDBigAttachmentsFollow,
        TDNoAttachments
    }

    /**
     * The type of event raised when a Database changes.
     */
    @InterfaceAudience.Public
    public static class ChangeEvent {

        private Database source;
        private boolean isExternal;
        private List<DocumentChange> changes;

        public ChangeEvent(Database source, boolean isExternal, List<DocumentChange> changes) {
            this.source = source;
            this.isExternal = isExternal;
            this.changes = changes;
        }

        public Database getSource() {
            return source;
        }
        public boolean isExternal() {
            return isExternal;
        }
        public List<DocumentChange> getChanges() {
            return changes;
        }
    }

    /**
     * A delegate that can be used to listen for Database changes.
     */
    @InterfaceAudience.Public
    public static interface ChangeListener {
        public void changed(ChangeEvent event);
    }

    /**
     * Get the database's name.
     */
    @InterfaceAudience.Public
    public String getName();

    /**
     * The database manager that owns this database.
     */
    @InterfaceAudience.Public
    public Manager getManager();

    /**
     * The number of documents in the database.
     */
    @InterfaceAudience.Public
    public int getDocumentCount();

    /**
     * The latest sequence number used.  Every new revision is assigned a new sequence number,
     * so this property increases monotonically as changes are made to the database. It can be
     * used to check whether the database has changed between two points in time.
     */
    @InterfaceAudience.Public
    public long getLastSequenceNumber();

    /**
     * Get all the replicators associated with this database.
     */
    @InterfaceAudience.Public
    public List<Replication> getAllReplications();

    /**
     * Compacts the database file by purging non-current JSON bodies, pruning revisions older than
     * the maxRevTreeDepth, deleting unused attachment files, and vacuuming the SQLite database.
     */
    @InterfaceAudience.Public
    public void compact() throws CouchbaseLiteException;

    /**
     * Deletes the database.
     *
     * @throws java.lang.RuntimeException
     */
    @InterfaceAudience.Public
    public void delete() throws CouchbaseLiteException;

    /**
     * Instantiates a Document object with the given ID.
     * Doesn't touch the on-disk sqliteDb; a document with that ID doesn't
     * even need to exist yet. CBLDocuments are cached, so there will
     * never be more than one instance (in this sqliteDb) at a time with
     * the same documentID.
     *
     * NOTE: the caching described above is not implemented yet
     *
     * @param documentId
     * @return
     */
    @InterfaceAudience.Public
    public Document getDocument(String documentId);

    /**
     * Gets the Document with the given id, or null if it does not exist.
     */
    @InterfaceAudience.Public
    public Document getExistingDocument(String documentId) ;

    /**
     * Creates a new Document object with no properties and a new (random) UUID.
     * The document will be saved to the database when you call -createRevision: on it.
     */
    @InterfaceAudience.Public
    public Document createDocument();

    /**
     * Returns the contents of the local document with the given ID, or nil if none exists.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getExistingLocalDocument(String documentId);

    /**
     * Sets the contents of the local document with the given ID. Unlike CouchDB, no revision-ID
     * checking is done; the put always succeeds. If the properties dictionary is nil, the document
     * will be deleted.
     */
    @InterfaceAudience.Public
    public boolean putLocalDocument(String id, Map<String, Object> properties) throws CouchbaseLiteException;

    /**
     * Deletes the local document with the given ID.
     */
    @InterfaceAudience.Public
    public boolean deleteLocalDocument(String id) throws CouchbaseLiteException;

    /**
     * Returns a query that matches all documents in the database.
     * This is like querying an imaginary view that emits every document's ID as a key.
     */
    @InterfaceAudience.Public
    public Query createAllDocumentsQuery();

    /**
     * Returns a View object for the view with the given name.
     * (This succeeds even if the view doesn't already exist, but the view won't be added to
     * the database until the View is assigned a map function.)
     */
    @InterfaceAudience.Public
    public View getView(String name);

    /**
     * Returns the existing View with the given name, or nil if none.
     */
    @InterfaceAudience.Public
    public View getExistingView(String name);

    /**
     * Returns the existing document validation function (block) registered with the given name.
     * Note that validations are not persistent -- you have to re-register them on every launch.
     */
    @InterfaceAudience.Public
    public Validator getValidation(String name);

    /**
     * Defines or clears a named document validation function.
     * Before any change to the database, all registered validation functions are called and given a
     * chance to reject it. (This includes incoming changes from a pull replication.)
     */
    @InterfaceAudience.Public
    public void setValidation(String name, Validator validator);

    /**
     * Returns the existing filter function (block) registered with the given name.
     * Note that filters are not persistent -- you have to re-register them on every launch.
     */
    @InterfaceAudience.Public
    public ReplicationFilter getFilter(String filterName);

    /**
     * Define or clear a named filter function.
     *
     * Filters are used by push replications to choose which documents to send.
     */
    @InterfaceAudience.Public
    public void setFilter(String filterName, ReplicationFilter filter);

    /**
     * Runs the block within a transaction. If the block returns NO, the transaction is rolled back.
     * Use this when performing bulk write operations like multiple inserts/updates;
     * it saves the overhead of multiple SQLite commits, greatly improving performance.
     *
     * Does not commit the transaction if the code throws an Exception.
     *
     * TODO: the iOS version has a retry loop, so there should be one here too
     *
     * @param transactionalTask
     */
    @InterfaceAudience.Public
    public boolean runInTransaction(TransactionalTask transactionalTask);

    /**
     * Runs the delegate asynchronously.
     */
    @InterfaceAudience.Public
    public Future runAsync(final AsyncTask asyncTask);

    /**
     * Creates a new Replication that will push to the target Database at the given url.
     *
     * @param remote the remote URL to push to
     * @return A new Replication that will push to the target Database at the given url.
     */
    @InterfaceAudience.Public
    public Replication createPushReplication(URL remote);

    /**
     * Creates a new Replication that will pull from the source Database at the given url.
     *
     * @param remote the remote URL to pull from
     * @return A new Replication that will pull from the source Database at the given url.
     */
    @InterfaceAudience.Public
    public Replication createPullReplication(URL remote);

    /**
     * Adds a Database change delegate that will be called whenever a Document within the Database changes.
     * @param listener
     */
    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener listener);

    /**
     * Removes the specified delegate as a listener for the Database change event.
     * @param listener
     */
    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener listener);

    /**
     * Returns a string representation of this database.
     */
    @InterfaceAudience.Public
    public String toString();

    /**
     * Get the maximum depth of a document's revision tree (or, max length of its revision history.)
     * Revisions older than this limit will be deleted during a -compact: operation.
     * Smaller values save space, at the expense of making document conflicts somewhat more likely.
     */
    @InterfaceAudience.Public
    public int getMaxRevTreeDepth();

    /**
     * Set the maximum depth of a document's revision tree (or, max length of its revision history.)
     * Revisions older than this limit will be deleted during a -compact: operation.
     * Smaller values save space, at the expense of making document conflicts somewhat more likely.
     */
    @InterfaceAudience.Public
    public void setMaxRevTreeDepth(int maxRevTreeDepth);

    /** PRIVATE METHODS **/

    /**
     * Returns the already-instantiated cached Document with the given ID, or nil if none is yet cached.
     * @exclude
     */
    @InterfaceAudience.Private
    public Document getCachedDocument(String documentID);

    /**
     * Empties the cache of recently used Document objects.
     * API calls will now instantiate and return new instances.
     * @exclude
     */
    @InterfaceAudience.Private
    public void clearDocumentCache();

    /**
     * Get all the active replicators associated with this database.
     */
    @InterfaceAudience.Private
    public List<Replication> getActiveReplications();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void removeDocumentFromCache(Document document);


    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean exists();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getAttachmentStorePath();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean initialize(String statements) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean open();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean close();
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getPath();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public SQLiteStorageEngine getDatabase();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public BlobStore getAttachments();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public BlobStoreWriter getAttachmentWriter();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long totalDataSize() ;

    /**
     * Begins a database transaction. Transactions can nest.
     * Every beginTransaction() must be balanced by a later endTransaction()
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean beginTransaction() ;

    /**
     * Commits or aborts (rolls back) a transaction.
     *
     * @param commit If true, commits; if false, aborts and rolls back, undoing all changes made since the matching -beginTransaction call, *including* any committed nested transactions.
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean endTransaction(boolean commit);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String privateUUID() ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String publicUUID();

    /** GETTING DOCUMENTS: **/

    /**
     * Splices the contents of an NSDictionary into JSON data (that already represents a dict), without parsing the JSON.
     * @exclude
     */
    @InterfaceAudience.Private
    public  byte[] appendDictToJSON(byte[] json, Map<String,Object> dict);

    /**
     * Inserts the _id, _rev and _attachments properties into the JSON data and stores it in rev.
     * Rev must already have its revID and sequence properties set.
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String,Object> extraPropertiesForRevision(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions);

    /**
     * Inserts the _id, _rev and _attachments properties into the JSON data and stores it in rev.
     * Rev must already have its revID and sequence properties set.
     * @exclude
     */
    @InterfaceAudience.Private
    public void expandStoredJSONIntoRevisionWithAttachments(byte[] json, RevisionInternal rev, EnumSet<TDContentOptions> contentOptions);

    /**
     * @exclude
     */
    @SuppressWarnings("unchecked")
    @InterfaceAudience.Private
    public Map<String, Object> documentPropertiesFromJSON(byte[] json, String docId, String revId, boolean deleted, long sequence, EnumSet<TDContentOptions> contentOptions) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal getDocumentWithIDAndRev(String id, String rev, EnumSet<TDContentOptions> contentOptions);
    // TODO: Change to throw CouchbaseLiteException
    //public RevisionInternal getDocumentWithIDAndRev(String id, String rev, EnumSet<TDContentOptions> contentOptions)  throws CouchbaseLiteException ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean existsDocumentWithIDAndRev(String docId, String revId) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal loadRevisionBody(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) throws CouchbaseLiteException ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long getDocNumericID(String docId);

    /** HISTORY: **/

    /**
     * Returns all the known revisions (or all current/conflicting revisions) of a document.
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionList getAllRevisionsOfDocumentID(String docId, long docNumericID, boolean onlyCurrent);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionList getAllRevisionsOfDocumentID(String docId, boolean onlyCurrent) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public List<String> getConflictingRevisionIDsOfDocID(String docID) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public List<String>  getPossibleAncestorRevisionIDs (
            RevisionInternal rev,
            int limit,
            AtomicBoolean hasAttachment
            );
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String findCommonAncestorOf(RevisionInternal rev, List<String> revIDs);

    /**
     * Returns an array of TDRevs in reverse chronological order, starting with the given revision.
     * @exclude
     */
    @InterfaceAudience.Private
    public List<RevisionInternal> getRevisionHistory(RevisionInternal rev);

    /**
     * Returns the revision history as a _revisions dictionary, as returned by the REST API's ?revs=true option.
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String,Object> getRevisionHistoryDict(RevisionInternal rev);

    /**
     * Returns the revision history as a _revisions dictionary, as returned by the REST API's ?revs=true option.
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String,Object> getRevisionHistoryDictStartingFromAnyAncestor(RevisionInternal rev, List<String>ancestorRevIDs) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionList changesSince(long lastSeq, ChangesOptions options, ReplicationFilter filter) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean runFilter(ReplicationFilter filter, Map<String, Object> paramsIgnored, RevisionInternal rev);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getDesignDocFunction(String fnName, String key, List<String>outLanguageList) ;

    /** VIEWS: **/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public View registerView(View view);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public List<QueryRow> queryViewNamed(String viewName, QueryOptions options, List<Long> outLastSequence) throws CouchbaseLiteException ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public View makeAnonymousView();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public List<View> getAllViews();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Status deleteViewNamed(String name);

    /**
     * Hack because cursor interface does not support cursor.getColumnIndex("deleted") yet.
     * @exclude
     */
    @InterfaceAudience.Private
    public int getDeletedColumnIndex(QueryOptions options);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String,Object> getAllDocs(QueryOptions options) throws CouchbaseLiteException;

    /**
     * Returns the rev ID of the 'winning' revision of this document, and whether it's deleted.
     * @exclude
     */
    @InterfaceAudience.Private
    public String winningRevIDOfDoc(long docNumericId, AtomicBoolean outIsDeleted, AtomicBoolean outIsConflict) throws CouchbaseLiteException;


    /*************************************************************************************************/
    /*** Database+Attachments                                                                    ***/
    /*************************************************************************************************/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void insertAttachmentForSequenceWithNameAndType(InputStream contentStream, long sequence, String name, String contentType, int revpos) throws CouchbaseLiteException;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void insertAttachmentForSequenceWithNameAndType(long sequence, String name, String contentType, int revpos, BlobKey key) throws CouchbaseLiteException;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    void installAttachment(AttachmentInternal attachment, Map<String, Object> attachInfo) throws CouchbaseLiteException;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void copyAttachmentNamedFromSequenceToSequence(String name, long fromSeq, long toSeq) throws CouchbaseLiteException;

    /**
     * Returns the content and MIME type of an attachment
     * @exclude
     */
    @InterfaceAudience.Private
    public Attachment getAttachmentForSequence(long sequence, String filename) throws CouchbaseLiteException;

    public boolean sequenceHasAttachments(long sequence) ;

    /**
     * Constructs an "_attachments" dictionary for a revision, to be inserted in its JSON body.
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String,Object> getAttachmentsDictForSequenceWithContent(long sequence, EnumSet<TDContentOptions> contentOptions);

    @InterfaceAudience.Private
    public URL fileForAttachmentDict(Map<String,Object> attachmentDict);

    /**
     * Modifies a RevisionInternal's body by changing all attachments with revpos < minRevPos into stubs.
     *
     * @exclude
     * @param rev
     * @param minRevPos
     */
    @InterfaceAudience.Private
    public void stubOutAttachmentsIn(RevisionInternal rev, int minRevPos);

    // Replaces the "follows" key with the real attachment data in all attachments to 'doc'.
    public boolean inlineFollowingAttachmentsIn(RevisionInternal rev) ;

    /**
     * Given a newly-added revision, adds the necessary attachment rows to the sqliteDb and
     * stores inline attachments into the blob store.
     * @exclude
     */
    @InterfaceAudience.Private
    void processAttachmentsForRevision(Map<String, AttachmentInternal> attachments, RevisionInternal rev, long parentSequence) throws CouchbaseLiteException ;

    /**
     * Updates or deletes an attachment, creating a new document revision in the process.
     * Used by the PUT / DELETE methods called on attachment URLs.
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal updateAttachment(String filename, BlobStoreWriter body, String contentType, AttachmentInternal.AttachmentEncoding encoding, String docID, String oldRevID) throws CouchbaseLiteException ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void rememberAttachmentWritersForDigests(Map<String, BlobStoreWriter> blobsByDigest);
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void rememberAttachmentWriter(BlobStoreWriter writer);

    /**
     * Deletes obsolete attachments from the sqliteDb and blob store.
     * @exclude
     */
    @InterfaceAudience.Private
    public Status garbageCollectAttachments();

    /*************************************************************************************************/
    /*** Database+Insertion                                                                      ***/
    /*************************************************************************************************/

    /** DOCUMENT & REV IDS: **/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String generateIDForRevision(RevisionInternal rev, byte[] json, Map<String, AttachmentInternal> attachments, String previousRevisionId);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long insertDocumentID(String docId);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long getOrInsertDocNumericID(String docId);

    /** INSERTION: **/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public byte[] encodeDocumentJSON(RevisionInternal rev);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void notifyChange(RevisionInternal rev, RevisionInternal winningRev, URL source, boolean inConflict) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long insertRevision(RevisionInternal rev, long docNumericID, long parentSequence, boolean current, boolean hasAttachments, byte[] data);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, Status resultStatus) throws CouchbaseLiteException;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId,  boolean allowConflict) throws CouchbaseLiteException;

    /**
     * Stores a new (or initial) revision of a document.
     *
     * This is what's invoked by a PUT or POST. As with those, the previous revision ID must be supplied when necessary and the call will fail if it doesn't match.
     *
     * @param oldRev The revision to add. If the docID is null, a new UUID will be assigned. Its revID must be null. It must have a JSON body.
     * @param prevRevId The ID of the revision to replace (same as the "?rev=" parameter to a PUT), or null if this is a new document.
     * @param allowConflict If false, an error status 409 will be returned if the insertion would create a conflict, i.e. if the previous revision already has a child.
     * @param resultStatus On return, an HTTP status code indicating success or failure.
     * @return A new RevisionInternal with the docID, revID and sequence filled in (but no body).
     * @exclude
     */
    @SuppressWarnings("unchecked")
    @InterfaceAudience.Private
    public RevisionInternal putRevision(RevisionInternal oldRev, String prevRevId, boolean allowConflict, Status resultStatus) throws CouchbaseLiteException;

    /**
     * Inserts an already-existing revision replicated from a remote sqliteDb.
     *
     * It must already have a revision ID. This may create a conflict! The revision's history must be given; ancestor revision IDs that don't already exist locally will create phantom revisions with no content.
     * @exclude
     */
    @InterfaceAudience.Private
    public void forceInsert(RevisionInternal rev, List<String> revHistory, URL source) throws CouchbaseLiteException;

    /** VALIDATION **/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void validateRevision(RevisionInternal newRev, RevisionInternal oldRev, String parentRevID) throws CouchbaseLiteException;

    /*************************************************************************************************/
    /*** Database+Replication                                                                    ***/
    /*************************************************************************************************/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getActiveReplicator(URL remote, boolean push);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(URL remote, boolean push, boolean continuous, ScheduledExecutorService workExecutor);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(String sessionId);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public Replication getReplicator(URL remote, HttpClientFactory httpClientFactory, boolean push, boolean continuous, ScheduledExecutorService workExecutor);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String lastSequenceWithCheckpointId(String checkpointId);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean setLastSequence(String lastSequence, String checkpointId, boolean push);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public String getLastSequenceStored(String checkpointId, boolean push);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public int findMissingRevisions(RevisionList touchRevs) throws SQLException;

    /*************************************************************************************************/
    /*** Database+LocalDocs                                                                      ***/
    /*************************************************************************************************/

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal putLocalRevision(RevisionInternal revision, String prevRevID) throws CouchbaseLiteException;

    /**
     * Creates a one-shot query with the given map function. This is equivalent to creating an
     * anonymous View and then deleting it immediately after querying it. It may be useful during
     * development, but in general this is inefficient if this map will be used more than once,
     * because the entire view has to be regenerated from scratch every time.
     * @exclude
     */
    @InterfaceAudience.Private
    public Query slowQuery(Mapper map);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal getParentRevision(RevisionInternal rev);

    /**
     * Purges specific revisions, which deletes them completely from the local database _without_ adding a "tombstone" revision. It's as though they were never there.
     * This operation is described here: http://wiki.apache.org/couchdb/Purge_Documents
     * @param docsToRevs  A dictionary mapping document IDs to arrays of revision IDs.
     * @resultOn success will point to an NSDictionary with the same form as docsToRev, containing the doc/revision IDs that were actually removed.
     * @exclude
     */
    @InterfaceAudience.Private
    public Map<String, Object> purgeRevisions(final Map<String, List<String>> docsToRevs) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean replaceUUIDs();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public RevisionInternal getLocalDocument(String docID, String revID) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public long getStartTime();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void deleteLocalDocument(String docID, String revID) throws CouchbaseLiteException;

    /**
     * Set the database's name.
     * @exclude
     */
    @InterfaceAudience.Private
    public void setName(String name);

    /**
     * Prune revisions to the given max depth.  Eg, remove revisions older than that max depth,
     * which will reduce storage requirements.
     *
     * TODO: This implementation is a bit simplistic. It won't do quite the right thing in
     * histories with branches, if one branch stops much earlier than another. The shorter branch
     * will be deleted entirely except for its leaf revision. A more accurate pruning
     * would require an expensive full tree traversal. Hopefully this way is good enough.
     *
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Private
    public int pruneRevsToMaxDepth(int maxDepth) throws CouchbaseLiteException;

    /**
     * Is the database open?
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean isOpen();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void addReplication(Replication replication) ;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void forgetReplication(Replication replication);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void addActiveReplication(Replication replication);

    /**
     * Get the PersistentCookieStore associated with this database.
     * Will lazily create one if none exists.
     *
     * @exclude
     */
    @InterfaceAudience.Private
    public PersistentCookieStore getPersistentCookieStore();
}
