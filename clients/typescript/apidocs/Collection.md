# Collection

One collection of a database, typed by how it maps documents.

Obtained from `Database.collection(...)`. Every call borrows a pooled connection for the length of one request. A
collection must exist before its first write: a save into a missing one is refused with `404-11`.

## databaseName

The database the collection belongs to.

## name

The collection's name.

## create

Creates the collection. It must exist before the first write.

## drop

Drops the collection with its indexes, schema and triggers.

## save

Inserts or replaces one document. A document with an `_id` overwrites the stored one; without one, the server
generates it.

@param value the document
@returns the `_id` and whether it was an insert

## saveAll

Inserts or replaces many documents in one request.

@param values the documents; two with the same `_id` are refused with `400-3`
@returns the inserted and updated ids

## findById

Reads one document by its exact, case-sensitive `_id`.

@param id the `_id`
@param options `{ dirtyRead: true }` skips the collection lock and may see a write that is still being applied
@returns the document, or `undefined` when there is none

## delete

Deletes one document.

@param id the `_id`

## query

Starts a query over the whole collection. Add steps, then run it with `toArray()`, `count()` or `first()`.

@returns an empty pipeline

## indexes

@returns the collection's field indexes

## schema

@returns the collection's JSON Schema

## triggers

@returns the collection's triggers

## listen

Subscribes to a query, or to the whole collection when no query is given: the handler gets the current result
now, and the full result again whenever a write changes it. Subscriptions use dedicated connections, never pooled ones.

@param query optional pipeline to watch; script operators are refused in a listen
@param handler called in arrival order; an async handler finishes before the next update starts
@returns the subscription; close it to stop
