package com.couchbase.lite;

import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Created by hideki on 12/9/14.
 */
public class ViewUtil {
    private static ViewCompiler compiler;

    /**
     * The registered object, if any, that can compile map/reduce functions from source code.
     */
    @InterfaceAudience.Public
    public static ViewCompiler getCompiler() {
        return compiler;
    }

    /**
     * Registers an object that can compile map/reduce functions from source code.
     */
    @InterfaceAudience.Public
    public static void setCompiler(ViewCompiler compiler) {
        ViewUtil.compiler = compiler;
    }

    /**
     * Changes a maxKey into one that also extends to any key it matches as a prefix
     * @exclude
     */
    @InterfaceAudience.Private
    public static Object keyForPrefixMatch(Object key, int depth) {
        if (depth < 1) {
            return key;
        } else if (key instanceof String) {
            // Kludge: prefix match a string by appending max possible character value to it
            return (String)key + "\uffff";
        } else if (key instanceof List) {
            List<Object> nuKey = new ArrayList<Object>(((List<Object>)key));
            if (depth == 1) {
                nuKey.add(new HashMap<String,Object>());
            } else {
                Object lastObject = keyForPrefixMatch(nuKey.get(nuKey.size()-1), depth - 1);
                nuKey.set(nuKey.size()-1, lastObject);
            }
            return nuKey;
        } else {
            return key;
        }
    }

    /**
     * Are key1 and key2 grouped together at this groupLevel?
     * @exclude
     */
    @InterfaceAudience.Private
    public static boolean groupTogether(Object key1, Object key2, int groupLevel) {
        if(groupLevel == 0 || !(key1 instanceof List) || !(key2 instanceof List)) {
            return key1.equals(key2);
        }
        @SuppressWarnings("unchecked")
        List<Object> key1List = (List<Object>)key1;
        @SuppressWarnings("unchecked")
        List<Object> key2List = (List<Object>)key2;

        // if either key list is smaller than groupLevel and the key lists are different
        // sizes, they cannot be equal.
        if ((key1List.size() < groupLevel || key2List.size() < groupLevel) && key1List.size() != key2List.size()) {
            return false;
        }

        int end = Math.min(groupLevel, Math.min(key1List.size(), key2List.size()));
        for(int i = 0; i < end; ++i) {
            if(!key1List.get(i).equals(key2List.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the prefix of the key to use in the result row, at this groupLevel
     * @exclude
     */
    @SuppressWarnings("unchecked")
    @InterfaceAudience.Private
    public static Object groupKey(Object key, int groupLevel) {
        if(groupLevel > 0 && (key instanceof List) && (((List<Object>)key).size() > groupLevel)) {
            return ((List<Object>)key).subList(0, groupLevel);
        }
        else {
            return key;
        }
    }

    /**
     * Utility function to use in reduce blocks. Totals an array of Numbers.
     */
    @InterfaceAudience.Public
    public static double totalValues(List<Object>values) {
        double total = 0;
        for (Object object : values) {
            if(object instanceof Number) {
                Number number = (Number)object;
                total += number.doubleValue();
            } else {
                Log.w(Log.TAG_VIEW, "Warning non-numeric value found in totalValues: %s", object);
            }
        }
        return total;
    }
}
