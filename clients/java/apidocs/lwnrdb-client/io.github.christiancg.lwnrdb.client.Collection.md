One collection of a database, typed by the codec that maps its documents.

Obtained from `Database.collection(...)`. Every call borrows a pooled connection for the length of one request.

@param <T> the type documents are decoded to

## databaseName()

@return the database the collection belongs to

## name()

@return the collection's name

## create()

Creates the collection. It must exist before the first write: a save into a missing collection is refused with `404-11`.

## drop()

Drops the collection with its indexes, schema and triggers.

## save(T)

Inserts or replaces one document. A document with an `_id` overwrites the stored one; without one, the
server generates it.

@param value the document
@return the `_id` and whether it was an insert

## saveAll(List)

Inserts or replaces many documents in one request.

@param values the documents; two with the same `_id` are refused with `400-3`
@return the inserted and updated ids

## findById(String)

Reads one document by its exact, case-sensitive `_id`.

@param id the `_id`
@return the document, empty when there is none

## findById(String, ReadMode)

Reads one document, optionally without waiting for the collection's read lock.

@param id the `_id`
@param mode `DIRTY` skips the collection lock and may see a write that is still being applied
@return the document, empty when there is none

## delete(String)

Deletes one document.

@param id the `_id`

## deleteWhere(Filter)

Deletes every document that matches the filter, all or nothing. A filter matching more than the server's
`deleteMaxMatches` is refused with `400-44` and deletes nothing.

@param condition the documents to delete, built like a query filter
@return how many documents were deleted; `0` when none matched

## updateWhere(Filter, Object)

Updates every document that matches the filter, all or nothing, by merging the patch's top-level fields into it:
each one is added or replaced, every other field is kept. A filter matching more than the server's `updateMaxMatches`
is refused with `400-45`, and a patch that is empty or names `_id` with `400-1`; either refusal updates nothing.

@param condition the documents to update, built like a query filter
@param patch the fields to set: a `Document`, a `Map` or an object mapped like a saved document
@return how many documents were updated; `0` when none matched

## query()

Starts a query over the whole collection. Add steps, then run it with `toList()`, `count()` or `first()`.

@return an empty pipeline

## indexes()

@return the collection's field indexes

## schema()

@return the collection's JSON Schema

## triggers()

@return the collection's triggers

## listen(Query, ListenHandler)

Subscribes to a query: the handler gets its current result now, and the full result again whenever a write
changes it. Subscriptions share dedicated connections, never pooled ones.

@param query the pipeline to watch; script operators are refused in a listen
@param handler called on a single dispatcher thread, in arrival order
@return the subscription; close it to stop

## listen(ListenHandler)

Subscribes to the whole collection.

@param handler called on a single dispatcher thread, in arrival order
@return the subscription; close it to stop
