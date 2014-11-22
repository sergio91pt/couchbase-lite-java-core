package com.couchbase.lite;

import com.couchbase.lite.internal.AttachmentInternal;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.storage.SQLException;
import com.couchbase.lite.storage.SQLiteStorageEngine;
import com.couchbase.lite.support.HttpClientFactory;
import com.couchbase.lite.support.PersistentCookieStore;

import java.io.InputStream;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by hideki on 11/22/14.
 */
public class DatabaseCBForest implements Database {
    public String getName() {
        return null;
    }

    public Manager getManager() {
        return null;
    }

    public int getDocumentCount() {
        return 0;
    }

    public long getLastSequenceNumber() {
        return 0;
    }

    public List<Replication> getAllReplications() {
        return null;
    }

    public void compact() throws CouchbaseLiteException {

    }

    public void delete() throws CouchbaseLiteException {

    }

    public Document getDocument(String documentId) {
        return null;
    }

    public Document getExistingDocument(String documentId) {
        return null;
    }

    public Document createDocument() {
        return null;
    }

    public Map<String, Object> getExistingLocalDocument(String documentId) {
        return null;
    }

    public boolean putLocalDocument(String id, Map<String, Object> properties) throws CouchbaseLiteException {
        return false;
    }

    public boolean deleteLocalDocument(String id) throws CouchbaseLiteException {
        return false;
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
        return null;
    }

    public void setValidation(String name, Validator validator) {

    }

    public ReplicationFilter getFilter(String filterName) {
        return null;
    }

    public void setFilter(String filterName, ReplicationFilter filter) {

    }

    public boolean runInTransaction(TransactionalTask transactionalTask) {
        return false;
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

    public void addChangeListener(ChangeListener listener) {

    }

    public void removeChangeListener(ChangeListener listener) {

    }

    public int getMaxRevTreeDepth() {
        return 0;
    }

    public void setMaxRevTreeDepth(int maxRevTreeDepth) {

    }

    public Document getCachedDocument(String documentID) {
        return null;
    }

    public void clearDocumentCache() {

    }

    public List<Replication> getActiveReplications() {
        return null;
    }

    public void removeDocumentFromCache(Document document) {

    }

    public boolean exists() {
        return false;
    }

    public String getAttachmentStorePath() {
        return null;
    }

    public boolean initialize(String statements) {
        return false;
    }

    public boolean open() {
        return false;
    }

    public boolean close() {
        return false;
    }

    public String getPath() {
        return null;
    }

    public SQLiteStorageEngine getDatabase() {
        return null;
    }

    public BlobStore getAttachments() {
        return null;
    }

    public BlobStoreWriter getAttachmentWriter() {
        return null;
    }

    public long totalDataSize() {
        return 0;
    }

    public boolean beginTransaction() {
        return false;
    }

    public boolean endTransaction(boolean commit) {
        return false;
    }

    public String privateUUID() {
        return null;
    }

    public String publicUUID() {
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

    public RevisionInternal getDocumentWithIDAndRev(String id, String rev, EnumSet<TDContentOptions> contentOptions) {
        return null;
    }

    public boolean existsDocumentWithIDAndRev(String docId, String revId) {
        return false;
    }

    public RevisionInternal loadRevisionBody(RevisionInternal rev, EnumSet<TDContentOptions> contentOptions) throws CouchbaseLiteException {
        return null;
    }

    public long getDocNumericID(String docId) {
        return 0;
    }

    public RevisionList getAllRevisionsOfDocumentID(String docId, long docNumericID, boolean onlyCurrent) {
        return null;
    }

    public RevisionList getAllRevisionsOfDocumentID(String docId, boolean onlyCurrent) {
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

    public List<RevisionInternal> getRevisionHistory(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> getRevisionHistoryDict(RevisionInternal rev) {
        return null;
    }

    public Map<String, Object> getRevisionHistoryDictStartingFromAnyAncestor(RevisionInternal rev, List<String> ancestorRevIDs) {
        return null;
    }

    public RevisionList changesSince(long lastSeq, ChangesOptions options, ReplicationFilter filter) {
        return null;
    }

    public boolean runFilter(ReplicationFilter filter, Map<String, Object> paramsIgnored, RevisionInternal rev) {
        return false;
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

    public void insertAttachmentForSequenceWithNameAndType(InputStream contentStream, long sequence, String name, String contentType, int revpos) throws CouchbaseLiteException {

    }

    public void insertAttachmentForSequenceWithNameAndType(long sequence, String name, String contentType, int revpos, BlobKey key) throws CouchbaseLiteException {

    }

    public void installAttachment(AttachmentInternal attachment, Map<String, Object> attachInfo) throws CouchbaseLiteException {

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

    public URL fileForAttachmentDict(Map<String, Object> attachmentDict) {
        return null;
    }

    public void stubOutAttachmentsIn(RevisionInternal rev, int minRevPos) {

    }

    public boolean inlineFollowingAttachmentsIn(RevisionInternal rev) {
        return false;
    }

    public void processAttachmentsForRevision(Map<String, AttachmentInternal> attachments, RevisionInternal rev, long parentSequence) throws CouchbaseLiteException {

    }

    public RevisionInternal updateAttachment(String filename, BlobStoreWriter body, String contentType, AttachmentInternal.AttachmentEncoding encoding, String docID, String oldRevID) throws CouchbaseLiteException {
        return null;
    }

    public void rememberAttachmentWritersForDigests(Map<String, BlobStoreWriter> blobsByDigest) {

    }

    public void rememberAttachmentWriter(BlobStoreWriter writer) {

    }

    public Status garbageCollectAttachments() {
        return null;
    }

    public String generateIDForRevision(RevisionInternal rev, byte[] json, Map<String, AttachmentInternal> attachments, String previousRevisionId) {
        return null;
    }

    public long insertDocumentID(String docId) {
        return 0;
    }

    public long getOrInsertDocNumericID(String docId) {
        return 0;
    }

    public byte[] encodeDocumentJSON(RevisionInternal rev) {
        return new byte[0];
    }

    public void notifyChange(RevisionInternal rev, RevisionInternal winningRev, URL source, boolean inConflict) {

    }

    public long insertRevision(RevisionInternal rev, long docNumericID, long parentSequence, boolean current, boolean hasAttachments, byte[] data) {
        return 0;
    }

    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, Status resultStatus) throws CouchbaseLiteException {
        return null;
    }

    public RevisionInternal putRevision(RevisionInternal rev, String prevRevId, boolean allowConflict) throws CouchbaseLiteException {
        return null;
    }

    public RevisionInternal putRevision(RevisionInternal oldRev, String prevRevId, boolean allowConflict, Status resultStatus) throws CouchbaseLiteException {
        return null;
    }

    public void forceInsert(RevisionInternal rev, List<String> revHistory, URL source) throws CouchbaseLiteException {

    }

    public void validateRevision(RevisionInternal newRev, RevisionInternal oldRev, String parentRevID) throws CouchbaseLiteException {

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

    public RevisionInternal putLocalRevision(RevisionInternal revision, String prevRevID) throws CouchbaseLiteException {
        return null;
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

    public RevisionInternal getLocalDocument(String docID, String revID) {
        return null;
    }

    public long getStartTime() {
        return 0;
    }

    public void deleteLocalDocument(String docID, String revID) throws CouchbaseLiteException {

    }

    public void setName(String name) {

    }

    public int pruneRevsToMaxDepth(int maxDepth) throws CouchbaseLiteException {
        return 0;
    }

    public boolean isOpen() {
        return false;
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
}
