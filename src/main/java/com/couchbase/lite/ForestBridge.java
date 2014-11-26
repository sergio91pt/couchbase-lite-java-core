package com.couchbase.lite;

import com.couchbase.cbforest.RevID;
import com.couchbase.cbforest.VersionedDocument;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.util.Log;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by hideki on 11/25/14.
 *
 * see CBLForestBridge.h and CBLForestBridge.mm
 */
public class ForestBridge {

    /**
     * static NSData* dataOfNode(const Revision* rev)
     */
    public static byte[] dataOfNode(com.couchbase.cbforest.Revision rev){
        String body = rev.getBody().getBuf();
        if(body!=null)
            return body.getBytes();
        return rev.readBody().getBuf().getBytes();
    }

    /**
     * + (BOOL) loadBodyOfRevisionObject: (CBL_MutableRevision*)rev
     *                           options: (CBLContentOptions)options
     *                               doc: (VersionedDocument&)doc
     */
    public static boolean loadBodyOfRevisionObject(RevisionInternal rev, EnumSet<Database.TDContentOptions> options, VersionedDocument doc){

        // If caller wants no body and no metadata props, this is a no-op:
        if(options.equals(EnumSet.of(Database.TDContentOptions.TDNoBody)))
            return true;

        com.couchbase.cbforest.Revision revNode = doc.get(new RevID(rev.getRevId()));
        if(revNode == null)
            return false;

        byte[] json = dataOfNode(revNode);
        if(json==null)
            return false;
        rev.setSequence(revNode.getSequence().longValue());


        Map<String,Object> extra = new HashMap<String,Object>();
        addContentProperties(options, extra, revNode);
        if(json.length > 0)
           rev.setJson(appendDictToJSON(extra, json));
        else
            rev.setProperties(extra);

        return true;
    }

    /**
     * + (CBL_MutableRevision*) revisionObjectFromForestDoc: (VersionedDocument&)doc
     *                                                revID: (NSString*)revID
     *                                              options: (CBLContentOptions)options
     */
    public static RevisionInternal revisionObjectFromForestDoc(VersionedDocument doc, String revID, EnumSet<Database.TDContentOptions> options){
        RevisionInternal rev = null;

        String docID = doc.getDocID().getBuf();
        if(doc.revsAvailable()){
            com.couchbase.cbforest.Revision revNode = doc.get(new RevID(revID));
            if(revNode == null)
                return null;
            rev = new RevisionInternal(docID, revID, revNode.isDeleted());
            rev.setSequence(revNode.getSequence().longValue());
        }
        else{
            rev = new RevisionInternal(docID, doc.getRevID().getBuf(), doc.isDeleted());
            rev.setSequence(doc.getSequence().longValue());
        }

        if(!loadBodyOfRevisionObject(rev, options, doc))
            return null;

        return rev;
    }

    /**
     * + (void) addContentProperties: (CBLContentOptions)options
     *                          into: (NSMutableDictionary*)dst
     *                           rev: (const Revision*)rev
     */
    @InterfaceAudience.Private
    public static void addContentProperties(EnumSet<Database.TDContentOptions> options, Map<String,Object> dst, com.couchbase.cbforest.Revision rev) {

        String revID = rev.getRevID().getBuf();
        assert(revID!=null);
        // I am not sure if downcast is possible with JNI
        com.couchbase.cbforest.VersionedDocument doc = (com.couchbase.cbforest.VersionedDocument)rev.getOwner();
        String docID = doc.getDocID().getBuf();
        dst.put("_id", docID);
        dst.put("_rev", revID);
        if(rev.isDeleted())
            dst.put("_deleted", true);

        // Get more optional stuff to put in the properties:
        if(options.contains(Database.TDContentOptions.TDIncludeLocalSeq)) {
            Long localSeq = rev.getSequence().longValue();
            dst.put("_local_seq", localSeq);
        }

        // TODO: Keep implement!!!!!
        if(options.contains(Database.TDContentOptions.TDIncludeRevs)) {
            Long localSeq = rev.getSequence().longValue();
            dst.put("_local_seq", localSeq);
            //Map<String,Object> revHistory = getRevisionHistoryDict(rev);
        }
    }

    /**
     * Splices the contents of an NSDictionary into JSON data (that already represents a dict), without parsing the JSON.
     *
     * Notes: 1) This code is from Database.java
     *        2) in iOS - CBLJSON.m - + (NSData*) appendDictionary: (NSDictionary*)dict
     *                                        toJSONDictionaryData: (NSData*)json
     */
    public static byte[] appendDictToJSON(Map<String,Object> dict, byte[] json) {
        if(dict.size() == 0) {
            return json;
        }

        byte[] extraJSON = null;
        try {
            extraJSON = Manager.getObjectMapper().writeValueAsBytes(dict);
        } catch (Exception e) {
            Log.e(Database.TAG, "Error convert extra JSON to bytes", e);
            return null;
        }

        int jsonLength = json.length;
        int extraLength = extraJSON.length;
        if(jsonLength == 2) { // Original JSON was empty
            return extraJSON;
        }
        byte[] newJson = new byte[jsonLength + extraLength - 1];
        System.arraycopy(json, 0, newJson, 0, jsonLength - 1);  // Copy json w/o trailing '}'
        newJson[jsonLength - 1] = ',';  // Add a ','
        System.arraycopy(extraJSON, 1, newJson, jsonLength, extraLength - 1);
        return newJson;
    }
}
