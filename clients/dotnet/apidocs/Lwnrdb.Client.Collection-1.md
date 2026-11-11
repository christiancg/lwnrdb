One collection of a database, typed by the codec that maps its documents.

Obtained from `Database.Collection`. Every call borrows a pooled connection for the length of one request. Use `Collection<Document>` for schema-less access, where `Query()` is the way to query; LINQ is meant for mapped types.

@typeparam T the type documents are decoded to

## AsQueryable()
Starts a LINQ query over the whole collection. Translation, supported operators and the differences from LINQ to Objects are described on `LwnrdbQueryable<T>`.

@returns a query that is translated and sent when it is enumerated or awaited

## CreateAsync(CancellationToken)
Creates the collection. It must exist before the first write: a save into a missing collection is refused with `404-11`.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown

## DeleteAsync(string, CancellationToken)
Deletes one document.

@param id the document's `_id`, case-sensitive
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.NotFoundException when the document does not exist

## DropAsync(CancellationToken)
Drops the collection with its indexes, schema and triggers.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown

## FindByIdAsync(string, bool, CancellationToken)
Reads one document, optionally without waiting for the collection's read lock.

@param id the document's `_id`, case-sensitive
@param dirtyRead when true, the collection lock is skipped and the read may see a write that is still being applied
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the document, or the default value of `T` when there is none

## FindByIdAsync(string, CancellationToken)
Reads one document by its exact, case-sensitive `_id`.

@param id the document's `_id`
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the document, or the default value of `T` when there is none

## Indexes()
Returns the collection's field indexes.

@returns the indexes facade

## ListenAsync(ListenHandler<T>, CancellationToken)
Subscribes to the whole collection: the handler gets its current content now, and the full result again whenever a write changes it. Listens use dedicated connections, never pooled ones.

@param handler receives the updates, in arrival order, one at a time
@param ct cancels the registration
@returns the subscription; dispose it to stop

## ListenAsync(Query<T>, ListenHandler<T>, CancellationToken)
Subscribes to a query: the handler gets its current result now, and the full result again whenever a write changes it. Listens use dedicated connections, never pooled ones.

@param query the pipeline to watch; script steps are refused in a listen
@param handler receives the updates, in arrival order, one at a time
@param ct cancels the registration
@returns the subscription; dispose it to stop
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-19` when the query holds a script

## ListenAsync(IQueryable<T>, ListenHandler<T>, CancellationToken)
Subscribes to a LINQ query, translated with `ToQuery`. It is refused when the query cannot be expressed as a fluent query, for example when it ends in `Select`.

@param query a query from `AsQueryable()`
@param handler receives the updates, in arrival order, one at a time
@param ct cancels the registration
@returns the subscription; dispose it to stop
@exception System.NotSupportedException when the query does not return the collection's documents

## Query()
Starts a fluent query over the whole collection. Add steps, then run it with `ToListAsync`, `CountAsync` or `FirstOrDefaultAsync`.

@returns an empty pipeline

## SaveAllAsync(IEnumerable<T>, CancellationToken)
Inserts or replaces many documents in one request.

@param values the documents; two with the same `_id` are refused with `400-3`
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the inserted and updated ids
@exception Lwnrdb.Client.Errors.MappingException when a value does not map to a document

## SaveAsync(T, CancellationToken)
Inserts or replaces one document. A document with an `_id` overwrites the stored one; without one, the server generates it.

@param value the document
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the `_id` and whether it was an insert
@exception Lwnrdb.Client.Errors.MappingException when the value does not map to a document, for example a non-finite number or an integer beyond 2^53

## Schema()
Returns the collection's JSON Schema.

@returns the schema facade

## Triggers()
Returns the collection's triggers.

@returns the triggers facade

## DatabaseName
The database the collection belongs to.

## Name
The collection's name.
