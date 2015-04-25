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
import com.couchbase.lite.util.Log;

import java.io.IOException;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A Couchbase Lite Document Attachment.
 */
public final class Attachment {

    /**
     * The owning document revision.
     */
    private Revision revision;

    /**
     * Whether or not this attachment is gzipped
     */
    private boolean gzipped;

    /**
     * The filename.
     */
    private String name;

    /**
     * The CouchbaseLite metadata about the attachment, that lives in the document.
     */
    private Map<String, Object> metadata;

    /**
     * The body data.
     */
    private InputStream body;

    /**
     * Constructor
     */
    @InterfaceAudience.Private
    /* package */ Attachment(InputStream contentStream, String contentType) {
        this.body = contentStream;
        metadata = new HashMap<String, Object>();
        metadata.put("content_type", contentType);
        metadata.put("follows", true);
        this.gzipped = false;
    }

    /**
     * Constructor
     */
    @InterfaceAudience.Private
    /* package */ Attachment(Revision revision, String name, Map<String, Object> metadata) {
        this.revision = revision;
        this.name = name;
        this.metadata = metadata;
        this.gzipped = false;
    }

    /**
     * Get the owning document revision.
     */
    @InterfaceAudience.Public
    public Revision getRevision() {
        return revision;
    }


    /**
     * Get the owning document.
     */
    @InterfaceAudience.Public
    public Document getDocument() {
        return revision.getDocument();
    }

    /**
     * Get the filename.
     */
    @InterfaceAudience.Public
    public String getName() {
        return name;
    }

    /**
     * Get the MIME type of the contents.
     */
    @InterfaceAudience.Public
    public String getContentType() {
        return (String) metadata.get("content_type");
    }


    /**
     * Get the content (aka 'body') data.
     * @throws CouchbaseLiteException
     */
    @InterfaceAudience.Public
    public InputStream getContent() throws CouchbaseLiteException {
        if (body != null) {
            return body;
        } else {
            Database db = revision.getDatabase();
            long sequence = getAttachmentSequence();
            if (sequence == 0) {
                throw new CouchbaseLiteException(Status.INTERNAL_SERVER_ERROR);
            }
            Attachment attachment = db.getAttachmentForSequence(sequence, this.name);
            body = attachment.getContent();
            return body;
        }
    }

    private long getAttachmentSequence() {
        long sequence = revision.getSequence();
        if (sequence == 0) {
            sequence = revision.getParentSequence();
        }
        return sequence;
    }

    /**
     * This is just for compatibility with iOS implementation.
     *
     * @exclude
     */
    @InterfaceAudience.Private
    public URL getContentURL(){
        try {
            long sequence = getAttachmentSequence();
            if (sequence > 0) {
                Database db = revision.getDatabase();
                //Attachment attachment = db.getAttachmentForSequence(sequence, this.name);
                String path = db.getAttachmentPathForSequence(sequence, this.name);
                if (path != null) {
                    return new File(path).toURI().toURL();
                }
            }
        } catch(Exception e){
            Log.d(Log.TAG_DATABASE, e.getMessage());
        }
        return null;
    }

    /**
     * Get the length in bytes of the contents.
     */
    @InterfaceAudience.Public
    public long getLength() {
        Number length = (Number) metadata.get("length");
        if (length != null) {
            return length.longValue();
        }
        else {
            return 0;
        }
    }

    /**
     * The CouchbaseLite metadata about the attachment, that lives in the document.
     */
    @InterfaceAudience.Public
    public Map<String, Object> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }

    @InterfaceAudience.Private
    /* package */ void setName(String name) {
        this.name = name;
    }

    @InterfaceAudience.Private
    /* package */ void setRevision(Revision revision) {
        this.revision = revision;
    }

    @InterfaceAudience.Private
    /* package */ InputStream getBodyIfNew() {
        return body;
    }

    /**
     * Goes through an _attachments dictionary and replaces any values that are Attachment objects
     * with proper JSON metadata dicts. It registers the attachment bodies with the blob store and sets
     * the metadata 'digest' and 'follows' properties accordingly.
     */
    @InterfaceAudience.Private
    /* package */ static Map<String, Object> installAttachmentBodies(Map<String, Object> attachments, Database database) throws CouchbaseLiteException {

        Map<String, Object> updatedAttachments = new HashMap<String, Object>();
        for (String name : attachments.keySet()) {
            Object value = attachments.get(name);
            if (value instanceof Attachment) {
                Attachment attachment = (Attachment) value;
                Map<String, Object> metadataMutable = new HashMap<String, Object>();
                metadataMutable.putAll(attachment.getMetadata());
                InputStream body = attachment.getBodyIfNew();
                if (body != null) {
                    // Copy attachment body into the database's blob store:
                    BlobStoreWriter writer;
                    try {
                        writer = blobStoreWriterForBody(body, database);
                    } catch (IOException e) {
                        throw new CouchbaseLiteException(e.getMessage(), Status.STATUS_ATTACHMENT_ERROR);
                    }
                    metadataMutable.put("length", (long)writer.getLength());
                    metadataMutable.put("digest", writer.mD5DigestString());
                    metadataMutable.put("follows", true);
                    database.rememberAttachmentWriter(writer);
                }
                updatedAttachments.put(name, metadataMutable);
            }
            else if (value instanceof AttachmentInternal) {
                throw new IllegalArgumentException("AttachmentInternal objects not expected here.  Could indicate a bug");
            }
            else if (value != null) {
                updatedAttachments.put(name, value);
            }
        }
        return updatedAttachments;
    }

    @InterfaceAudience.Private
    /* package */ static BlobStoreWriter blobStoreWriterForBody(InputStream body, Database database) throws IOException {
        BlobStoreWriter writer = database.getAttachmentWriter();
        try {
            writer.read(body);
            writer.finish();
        } catch (IOException e) {
            writer.cancel();
            throw e;
        }
        return writer;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public boolean getGZipped() {
        return gzipped;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public void setGZipped(boolean gzipped) {
        this.gzipped = gzipped;
    }
}
