package com.couchbase.lite;

import com.couchbase.lite.internal.RevisionInternal;

import java.util.List;
import java.util.Map;

/**
 * Created by hideki on 11/22/14.
 */
public class DocumentCBForest implements Document{
    public Database getDatabase() {
        return null;
    }

    public String getId() {
        return null;
    }

    public boolean isDeleted() {
        return false;
    }

    public String getCurrentRevisionId() {
        return null;
    }

    public SavedRevision getCurrentRevision() {
        return null;
    }

    public List<SavedRevision> getRevisionHistory() throws CouchbaseLiteException {
        return null;
    }

    public List<SavedRevision> getConflictingRevisions() throws CouchbaseLiteException {
        return null;
    }

    public List<SavedRevision> getLeafRevisions() throws CouchbaseLiteException {
        return null;
    }

    public Map<String, Object> getProperties() {
        return null;
    }

    public Map<String, Object> getUserProperties() {
        return null;
    }

    public boolean delete() throws CouchbaseLiteException {
        return false;
    }

    public void purge() throws CouchbaseLiteException {

    }

    public SavedRevision getRevision(String id) {
        return null;
    }

    public UnsavedRevision createRevision() {
        return null;
    }

    public Object getProperty(String key) {
        return null;
    }

    public SavedRevision putProperties(Map<String, Object> properties) throws CouchbaseLiteException {
        return null;
    }

    public SavedRevision update(DocumentUpdater updater) throws CouchbaseLiteException {
        return null;
    }

    public void addChangeListener(ChangeListener changeListener) {

    }

    public void removeChangeListener(ChangeListener changeListener) {

    }

    public String getAbbreviatedId() {
        return null;
    }

    public void loadCurrentRevisionFrom(QueryRow row) {

    }

    public SavedRevision getRevisionFromRev(RevisionInternal internalRevision) {
        return null;
    }

    public SavedRevision putProperties(Map<String, Object> properties, String prevID, boolean allowConflict) throws CouchbaseLiteException {
        return null;
    }

    public void revisionAdded(DocumentChange documentChange) {

    }
}
