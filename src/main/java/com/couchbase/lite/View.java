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


import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.storage.Cursor;

import java.util.List;
import java.util.Map;

/**
 * Represents a view available in a database.
 */
public interface View {

    /**
     * @exclude
     */
    public static final int REDUCE_BATCH_SIZE = 100;

    /**
     * @exclude
     */
    public enum TDViewCollation {
        TDViewCollationUnicode, TDViewCollationRaw, TDViewCollationASCII
    }

    /**
     * Get the database that owns this view.
     */
    @InterfaceAudience.Public
    public Database getDatabase();

    /**
     * Get the name of the view.
     */
    @InterfaceAudience.Public
    public String getName();

    /**
     * The map function that controls how index rows are created from documents.
     */
    @InterfaceAudience.Public
    public Mapper getMap();

    /**
     * The optional reduce function, which aggregates together multiple rows.
     */
    @InterfaceAudience.Public
    public Reducer getReduce();

    /**
     * Is the view's index currently out of date?
     */
    @InterfaceAudience.Public
    public boolean isStale();

    /**
     * Get the last sequence number indexed so far.
     */
    @InterfaceAudience.Public
    public long getLastSequenceIndexed() ;

    /**
     * Defines a view that has no reduce function.
     * See setMapReduce() for more information.
     */
    @InterfaceAudience.Public
    public boolean setMap(Mapper mapBlock, String version);

    /**
     * Defines a view's functions.
     *
     * The view's definition is given as a class that conforms to the Mapper or
     * Reducer interface (or null to delete the view). The body of the block
     * should call the 'emit' object (passed in as a paramter) for every key/value pair
     * it wants to write to the view.
     *
     * Since the function itself is obviously not stored in the database (only a unique
     * string idenfitying it), you must re-define the view on every launch of the app!
     * If the database needs to rebuild the view but the function hasn't been defined yet,
     * it will fail and the view will be empty, causing weird problems later on.
     *
     * It is very important that this block be a law-abiding map function! As in other
     * languages, it must be a "pure" function, with no side effects, that always emits
     * the same values given the same input document. That means that it should not access
     * or change any external state; be careful, since callbacks make that so easy that you
     * might do it inadvertently!  The callback may be called on any thread, or on
     * multiple threads simultaneously. This won't be a problem if the code is "pure" as
     * described above, since it will as a consequence also be thread-safe.
     *
     */
    @InterfaceAudience.Public
    public boolean setMapReduce(Mapper mapBlock, Reducer reduceBlock, String version);


    /**
     * Deletes the view's persistent index. It will be regenerated on the next query.
     */
    @InterfaceAudience.Public
    public void deleteIndex();

    /**
     * Deletes the view, persistently.
     */
    @InterfaceAudience.Public
    public void delete();

    /**
     * Creates a new query object for this view. The query can be customized and then executed.
     */
    @InterfaceAudience.Public
    public Query createQuery();

    @InterfaceAudience.Private
    public int getViewId();

    @InterfaceAudience.Private
    public void databaseClosing();

    /*** Indexing ***/

    @InterfaceAudience.Private
    public String toJSONString(Object object);

    @InterfaceAudience.Private
    public TDViewCollation getCollation();

    @InterfaceAudience.Private
    public void setCollation(TDViewCollation collation);

    /**
     * Updates the view's index (incrementally) if necessary.
     * @return 200 if updated, 304 if already up-to-date, else an error code
     */
    @SuppressWarnings("unchecked")
    @InterfaceAudience.Private
    public void updateIndex() throws CouchbaseLiteException;

    @InterfaceAudience.Private
    public Cursor resultSetWithOptions(QueryOptions options);

    /*** Querying ***/

    @InterfaceAudience.Private
    public List<Map<String, Object>> dump();

    /**
     * Queries the view. Does NOT first update the index.
     *
     * @param options The options to use.
     * @return An array of QueryRow objects.
     */
    @InterfaceAudience.Private
    public List<QueryRow> queryWithOptions(QueryOptions options) throws CouchbaseLiteException;
}
