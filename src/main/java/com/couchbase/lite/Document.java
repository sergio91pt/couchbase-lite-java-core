package com.couchbase.lite;

import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;

import java.util.List;
import java.util.Map;

/**
 * A CouchbaseLite document.
 */
public interface Document {
    /**
     * A delegate that can be used to update a Document.
     */
    @InterfaceAudience.Public
    public static interface DocumentUpdater {

        /**
         * Document update delegate
         *
         * @param newRevision the unsaved revision about to be saved
         * @return True if the UnsavedRevision should be saved, otherwise false.
         */
        public boolean update(UnsavedRevision newRevision);

    }
    /**
     * The type of event raised when a Document changes. This event is not raised in response
     * to local Document changes.
     */
    @InterfaceAudience.Public
    public static class ChangeEvent {
        private Document source;
        private DocumentChange change;

        public ChangeEvent(Document source, DocumentChange documentChange) {
            this.source = source;
            this.change = documentChange;
        }

        public Document getSource() {
            return source;
        }

        public DocumentChange getChange() {
            return change;
        }
    }
    /**
     * A delegate that can be used to listen for Document changes.
     */
    @InterfaceAudience.Public
    public static interface ChangeListener {
        public void changed(ChangeEvent event);
    }

    /**
     * Get the document's owning database.
     */
    @InterfaceAudience.Public
    public Database getDatabase() ;

    /**
     * Get the document's ID
     */
    @InterfaceAudience.Public
    public String getId() ;

    /**
     * Is this document deleted? (That is, does its current revision have the '_deleted' property?)
     * @return boolean to indicate whether deleted or not
     */
    @InterfaceAudience.Public
    public boolean isDeleted() ;

    /**
     * Get the ID of the current revision
     */
    @InterfaceAudience.Public
    public String getCurrentRevisionId();

    /**
     * Get the current revision
     */
    @InterfaceAudience.Public
    public SavedRevision getCurrentRevision() ;

    /**
     * Returns the document's history as an array of CBLRevisions. (See SavedRevision's method.)
     *
     * @return document's history
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public List<SavedRevision> getRevisionHistory() throws CouchbaseLiteException ;

    /**
     * Returns all the current conflicting revisions of the document. If the document is not
     * in conflict, only the single current revision will be returned.
     *
     * @return all current conflicting revisions of the document
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public List<SavedRevision> getConflictingRevisions() throws CouchbaseLiteException ;

    /**
     * Returns all the leaf revisions in the document's revision tree,
     * including deleted revisions (i.e. previously-resolved conflicts.)
     *
     * @return all the leaf revisions in the document's revision tree
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public List<SavedRevision> getLeafRevisions() throws CouchbaseLiteException ;
    /**
     * The contents of the current revision of the document.
     * This is shorthand for self.currentRevision.properties.
     * Any keys in the dictionary that begin with "_", such as "_id" and "_rev", contain CouchbaseLite metadata.
     *
     * @return contents of the current revision of the document.
     */
    @InterfaceAudience.Public
    public Map<String,Object> getProperties() ;

    /**
     * The user-defined properties, without the ones reserved by CouchDB.
     * This is based on -properties, with every key whose name starts with "_" removed.
     *
     * @return user-defined properties, without the ones reserved by CouchDB.
     */
    @InterfaceAudience.Public
    public Map<String,Object> getUserProperties() ;

    /**
     * Deletes this document by adding a deletion revision.
     * This will be replicated to other databases.
     *
     * @return boolean to indicate whether deleted or not
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public boolean delete() throws CouchbaseLiteException;

    /**
     * Purges this document from the database; this is more than deletion, it forgets entirely about it.
     * The purge will NOT be replicated to other databases.
     *
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public void purge() throws CouchbaseLiteException ;

    /**
     * The revision with the specified ID.
     *
     *
     * @param id the revision ID
     * @return the SavedRevision object
     */
    @InterfaceAudience.Public
    public SavedRevision getRevision(String id) ;

    /**
     * Creates an unsaved new revision whose parent is the currentRevision,
     * or which will be the first revision if the document doesn't exist yet.
     * You can modify this revision's properties and attachments, then save it.
     * No change is made to the database until/unless you save the new revision.
     *
     * @return the newly created revision
     */
    @InterfaceAudience.Public
    public UnsavedRevision createRevision();
    /**
     * Shorthand for getProperties().get(key)
     */
    @InterfaceAudience.Public
    public Object getProperty(String key) ;

    /**
     * Saves a new revision. The properties dictionary must have a "_rev" property
     * whose ID matches the current revision's (as it will if it's a modified
     * copy of this document's .properties property.)
     *
     * @param properties the contents to be saved in the new revision
     * @return a new SavedRevision
     */
    @InterfaceAudience.Public
    public SavedRevision putProperties(Map<String,Object> properties) throws CouchbaseLiteException ;

    /**
     * Saves a new revision by letting the caller update the existing properties.
     * This method handles conflicts by retrying (calling the block again).
     * The DocumentUpdater implementation should modify the properties of the new revision and return YES to save or
     * NO to cancel. Be careful: the DocumentUpdater can be called multiple times if there is a conflict!
     *
     * @param updater the callback DocumentUpdater implementation.  Will be called on each
     *                attempt to save. Should update the given revision's properties and then
     *                return YES, or just return NO to cancel.
     * @return The new saved revision, or null on error or cancellation.
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public SavedRevision update(DocumentUpdater updater) throws CouchbaseLiteException;

    @InterfaceAudience.Public
    public void addChangeListener(ChangeListener changeListener) ;

    @InterfaceAudience.Public
    public void removeChangeListener(ChangeListener changeListener) ;

    /**
     * Get the document's abbreviated ID
     * @exclude
     */
    @InterfaceAudience.Private
    public String getAbbreviatedId();

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void loadCurrentRevisionFrom(QueryRow row);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public SavedRevision getRevisionFromRev(RevisionInternal internalRevision);

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public SavedRevision putProperties(Map<String, Object> properties, String prevID, boolean allowConflict) throws CouchbaseLiteException;

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void revisionAdded(DocumentChange documentChange);

}
