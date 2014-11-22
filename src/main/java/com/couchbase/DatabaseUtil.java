package com.couchbase;

import com.couchbase.lite.Misc;
import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.internal.RevisionInternal;
import com.couchbase.lite.util.CollectionUtils;
import com.couchbase.lite.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by hideki on 11/22/14.
 */
public class DatabaseUtil {
    /**
     * Parses the _revisions dict from a document into an array of revision ID strings
     * @exclude
     */
    @InterfaceAudience.Private
    public static List<String> parseCouchDBRevisionHistory(Map<String,Object> docProperties) {
        Map<String,Object> revisions = (Map<String,Object>)docProperties.get("_revisions");
        if(revisions == null) {
            return new ArrayList<String>();
        }
        List<String> revIDs = new ArrayList<String>((List<String>)revisions.get("ids"));
        if (revIDs == null || revIDs.isEmpty()) {
            return new ArrayList<String>();
        }
        Integer start = (Integer)revisions.get("start");
        if(start != null) {
            for(int i=0; i < revIDs.size(); i++) {
                String revID = revIDs.get(i);
                revIDs.set(i, Integer.toString(start--) + "-" + revID);
            }
        }
        return revIDs;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static boolean isValidDocumentId(String id) {
        // http://wiki.apache.org/couchdb/HTTP_Document_API#Documents
        if(id == null || id.length() == 0) {
            return false;
        }
        if(id.charAt(0) == '_') {
            return  (id.startsWith("_design/"));
        }
        return true;
        // "_local/*" is not a valid document ID. Local docs have their own API and shouldn't get here.
    }

    // Replaces attachment data whose revpos is < minRevPos with stubs.
    // If attachmentsFollow==YES, replaces data with "follows" key.
    public static void stubOutAttachmentsInRevBeforeRevPos(final RevisionInternal rev, final int minRevPos, final boolean attachmentsFollow) {
        if (minRevPos <= 1 && !attachmentsFollow) {
            return;
        }

        rev.mutateAttachments(new CollectionUtils.Functor<Map<String,Object>,Map<String,Object>>() {
            public Map<String, Object> invoke(Map<String, Object> attachment) {
                int revPos = 0;
                if (attachment.get("revpos") != null) {
                    revPos = (Integer)attachment.get("revpos");
                }

                boolean includeAttachment = (revPos == 0 || revPos >= minRevPos);
                boolean stubItOut = !includeAttachment && (attachment.get("stub") == null || (Boolean)attachment.get("stub") == false);
                boolean addFollows = includeAttachment && attachmentsFollow && (attachment.get("follows") == null || (Boolean)attachment.get("follows") == false);

                if (!stubItOut && !addFollows) {
                    return attachment;  // no change
                }

                // Need to modify attachment entry:
                Map<String, Object> editedAttachment = new HashMap<String, Object>(attachment);
                editedAttachment.remove("data");
                if (stubItOut) {
                    // ...then remove the 'data' and 'follows' key:
                    editedAttachment.remove("follows");
                    editedAttachment.put("stub",true);
                    Log.v(Log.TAG_SYNC, "Stubbed out attachment %s: revpos %d < %d", rev, revPos, minRevPos);
                } else if (addFollows) {
                    editedAttachment.remove("stub");
                    editedAttachment.put("follows",true);
                    Log.v(Log.TAG_SYNC, "Added 'follows' for attachment %s: revpos %d >= %d",rev, revPos, minRevPos);
                }
                return editedAttachment;
            }
        });
    }
    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static Map<String,Object> makeRevisionHistoryDict(List<RevisionInternal> history) {
        if(history == null) {
            return null;
        }

        // Try to extract descending numeric prefixes:
        List<String> suffixes = new ArrayList<String>();
        int start = -1;
        int lastRevNo = -1;
        for (RevisionInternal rev : history) {
            int revNo = DatabaseUtil.parseRevIDNumber(rev.getRevId());
            String suffix = DatabaseUtil.parseRevIDSuffix(rev.getRevId());
            if(revNo > 0 && suffix.length() > 0) {
                if(start < 0) {
                    start = revNo;
                }
                else if(revNo != lastRevNo - 1) {
                    start = -1;
                    break;
                }
                lastRevNo = revNo;
                suffixes.add(suffix);
            }
            else {
                start = -1;
                break;
            }
        }

        Map<String,Object> result = new HashMap<String,Object>();
        if(start == -1) {
            // we failed to build sequence, just stuff all the revs in list
            suffixes = new ArrayList<String>();
            for (RevisionInternal rev : history) {
                suffixes.add(rev.getRevId());
            }
        }
        else {
            result.put("start", start);
        }
        result.put("ids", suffixes);

        return result;
    }
    /**
     * Splits a revision ID into its generation number and opaque suffix string
     * @exclude
     */
    @InterfaceAudience.Private
    public static int parseRevIDNumber(String rev) {
        int result = -1;
        int dashPos = rev.indexOf("-");
        if(dashPos >= 0) {
            try {
                result = Integer.parseInt(rev.substring(0, dashPos));
            } catch (NumberFormatException e) {
                // ignore, let it return -1
            }
        }
        return result;
    }

    /**
     * Splits a revision ID into its generation number and opaque suffix string
     * @exclude
     */
    @InterfaceAudience.Private
    public static String parseRevIDSuffix(String rev) {
        String result = null;
        int dashPos = rev.indexOf("-");
        if(dashPos >= 0) {
            result = rev.substring(dashPos + 1);
        }
        return result;
    }

    /**
     * @exclude
     */
    @InterfaceAudience.Private
    public static String generateDocumentId() {
        return Misc.TDCreateUUID();
    }
}
