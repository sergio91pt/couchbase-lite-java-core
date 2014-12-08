package com.couchbase.lite.internal;

import com.couchbase.lite.BlobKey;
import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Status;
import com.couchbase.lite.support.Base64;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple container for attachment metadata.
 */
public class AttachmentInternal {

    public enum AttachmentEncoding {
        AttachmentEncodingNone, AttachmentEncodingGZIP
    }
    // in CBL_Attachment.h
    private String name = null;
    private String contentType = null;
    private BlobKey blobKey = null;
    private long length = 0;
    private long encodedLength = 0;
    private AttachmentEncoding encoding = null;
    private int revpos = 0;

    private String digest = null;
    private byte[] data = null;

    public AttachmentInternal(String name, String contentType) {
        this.name = name;
        this.contentType = contentType;
    }

    /**
     * in CBL_Attachment.m
     * - (instancetype) initWithName: (NSString*)name
     *                          info: (NSDictionary*)attachInfo
     *                        status: (CBLStatus*)outStatus
     */
    public AttachmentInternal(String name, Map<String, Object> attachInfo) throws CouchbaseLiteException {
        // name & content_type
        this(name, (String)attachInfo.get("content_type"));
        // length
        if (attachInfo.containsKey("length")) {
            Number length = (Number)attachInfo.get("length");
            this.length = length.longValue();
        }
        // encoded_length
        if (attachInfo.containsKey("encoded_length")) {
            Number encodedLength = (Number)attachInfo.get("encoded_length");
            this.encodedLength = encodedLength.longValue();
        }
        // digest
        if (attachInfo.containsKey("digest")) {
            this.digest = (String) attachInfo.get("digest");
            this.blobKey = new BlobKey(this.digest);
        }
        // encoding
        if (attachInfo.containsKey("encoding")) {
            String encoding = (String)attachInfo.get("encoding");
            if(encoding != null && encoding.length() > 0) {
                if (encoding != null && encoding.equalsIgnoreCase("gzip")) {
                    this.encoding = AttachmentInternal.AttachmentEncoding.AttachmentEncodingGZIP;
                } else {
                    throw new CouchbaseLiteException("Unknown encoding: " + encoding, Status.BAD_ENCODING);
                }
            }
        }

        String newContentBase64 = (String) attachInfo.get("data");
        if (newContentBase64 != null) {
            // If there's inline attachment data, decode and store it:
            try {
                this.data = Base64.decode(newContentBase64);
            } catch (IOException e) {
                throw new CouchbaseLiteException(e, Status.BAD_ENCODING);
            }
            setLength(this.data.length);
        }
        else if (attachInfo.containsKey("stub") &&
                ((Boolean)attachInfo.get("stub")).booleanValue()) {
            // This item is just a stub; validate and skip it
            int revPos = ((Integer)attachInfo.get("revpos")).intValue();
            if (revPos <= 0) {
                throw new CouchbaseLiteException(Status.BAD_ATTACHMENT);
            }
            this.revpos = revPos;
        }
        else if (attachInfo.containsKey("follows") &&
                ((Boolean)attachInfo.get("follows")).booleanValue()) {
            // I can't handle this myself; my caller will look it up from the digest
            if(this.digest == null)
                throw new CouchbaseLiteException(Status.BAD_ATTACHMENT);
        }
        else{
            throw new CouchbaseLiteException(Status.BAD_ATTACHMENT);
        }
    }

    /**
     * in CBL_Attachment.m
     * static NSString* blobKeyToDigest(CBLBlobKey key)
     */
    static private String blobKeyToDigest(BlobKey key){
        return key.base64Digest();
    }

    /**
     * in CBL_Attachment.m
     * - (NSDictionary*) asStubDictionary
     */
    public Map<String, Object> asStubDictionary(){
        Map<String, Object> dict = new HashMap<String, Object>();
        dict.put("stub", true);
        dict.put("digest",blobKeyToDigest(blobKey));
        dict.put("content_type", contentType);
        dict.put("revpos", revpos);
        dict.put("length", length);

        if(encodedLength > 0)
            dict.put("encoded_length", encodedLength);
        if(encoding!=null) {
            switch (encoding) {
                case AttachmentEncodingGZIP:
                    dict.put("encoding", "gzip");
                    break;
                case AttachmentEncodingNone:
                    break;
            }
        }
        return dict;
    }

    public boolean isValid() {
        if (encoding != AttachmentEncoding.AttachmentEncodingNone) {
            if (encodedLength == 0 && length > 0) {
                return false;
            }
        }
        else if (encodedLength > 0) {
            return false;
        }
        if (revpos == 0) {
            return false;
        }
        return true;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public BlobKey getBlobKey() {
        return blobKey;
    }

    public void setBlobKey(BlobKey blobKey) {
        this.blobKey = blobKey;
    }

    public long getLength() {
        return length;
    }

    public void setLength(long length) {
        this.length = length;
    }

    public long getEncodedLength() {
        return encodedLength;
    }

    public void setEncodedLength(long encodedLength) {
        this.encodedLength = encodedLength;
    }

    public AttachmentEncoding getEncoding() {
        return encoding;
    }

    public void setEncoding(AttachmentEncoding encoding) {
        this.encoding = encoding;
    }

    public int getRevpos() {
        return revpos;
    }

    public void setRevpos(int revpos) {
        this.revpos = revpos;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }
}

