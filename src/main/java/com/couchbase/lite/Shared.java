package com.couchbase.lite;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by hideki on 12/9/14.
 *
 * Java version of CBL_Shared
 */
public class Shared {

    Map<String, Map<String, Map<String, Object>>> databases = null;

    public Shared(){
        databases = new HashMap<String, Map<String, Map<String, Object>>>();
    }

    public synchronized void setValue(Object value, String type, String name, String dbName){
        Map<String, Map<String, Object>> dbDict = databases.get(dbName);
        if(dbDict == null) {
            dbDict = new HashMap<String, Map<String, Object>>();
            databases.put(dbName, dbDict);
        }
        Map<String, Object> typeDict = dbDict.get(type);
        if(typeDict == null) {
            typeDict = new HashMap<String, Object>();
            dbDict.put(type, typeDict);
        }
        typeDict.put(name, value);
    }

    public synchronized Object valueFor(String type, String name, String dbName){
        try {
            return databases.get(dbName).get(type).get(name);
        }catch(Exception e){
            return null;
        }
    }
}

