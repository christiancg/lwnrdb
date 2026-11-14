# AsyncCollection

One collection of a database, typed by how it maps documents: `AsyncCollection[T, R]` saves `T` and reads `R`.

A collection must exist before its first write: a save into a missing one is refused with `404-11`.

The sync class `Collection` has the same members; its methods block and return the value directly.

## database_name

The database the collection belongs to.

## name

The collection's name.

## create

Creates the collection. It must exist before the first write.

## drop

Drops the collection with its indexes, schema and triggers.

## save

Inserts or replaces one document. A document with an `_id` overwrites the stored one; without one, the
server generates it.

Returns:
    the `_id` and whether it was an insert

## save_all

Inserts or replaces many documents in one request.

Args:
    values: the documents; two with the same `_id` are refused with `400-3`

Returns:
    the inserted and the updated ids

## find_by_id

Reads one document by its `_id`.

Args:
    id: the document's `_id`, compared exactly
    dirty_read: read without waiting for writers holding the collection's lock

Returns:
    the document, or `None` when there is none

## delete

Deletes one document by its `_id`.

## delete_where

Deletes every document that matches the filter, all or nothing, and returns how many it deleted (`0` when none
matched). A filter matching more than the server's `deleteMaxMatches` is refused with `400-44` and deletes nothing.

## query

Starts a query over the collection. See `QueryBuilder` for the steps.

## indexes

Returns the index operations of this collection.

## schema

Returns the JSON Schema operations of this collection.

## triggers

Returns the trigger operations of this collection.

## listen

Registers a live query: the handler receives the query's results now and again after every change
that alters them.

The handler is a callable taking a `ListenUpdate`, or an object with `on_update` and optionally `on_ended`
(the listen ended, for example because the collection was dropped) and `on_error`. Updates of one
connection arrive in order. In the async client a handler may be a coroutine function; in the sync client
it runs on a dedicated thread and may call the client.

Args:
    handler: receives the updates
    query: the query to watch; every document of the collection when omitted

Returns:
    the subscription; close it to stop listening
