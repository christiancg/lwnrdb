One collection seen from inside a transaction. Every request goes through the transaction's pinned connection and is buffered by the server until the commit.

Obtained from `Transaction.Collection`. Reads inside the transaction see its own writes.

@typeparam T the type documents are decoded to

## AsQueryable()
Starts a LINQ query over the collection, sent on the transaction's connection.

@returns a query that is translated and sent when it is enumerated or awaited

## DeleteAsync(string, CancellationToken)
Deletes one document as part of the transaction.

@param id the document's `_id`, case-sensitive
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown

## DeleteWhereAsync(Filter, CancellationToken)
Deletes every document the filter matches in the transaction's view, its own writes included, as part of the
transaction.

@param condition the documents to delete, built like a query filter
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns how many documents matched and were buffered

## DeleteWhereAsync(Expression<Func<T, bool>>, CancellationToken)
Deletes every document the predicate matches as part of the transaction, translated to a server filter as `Where` is.

@param predicate the documents to delete, over the stored members of `T`
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns how many documents matched and were buffered
@exception System.NotSupportedException when the predicate cannot run on the server

## FindByIdAsync(string, CancellationToken)
Reads one document by its exact, case-sensitive `_id`, seeing the transaction's own writes.

@param id the document's `_id`
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns the document, or the default value of `T` when there is none

## InsertAsync(T, CancellationToken)
Inserts one document as part of the transaction, never replacing one. A document whose `_id` the transaction's view
already holds, its own writes included, is refused with `409-18` and nothing is buffered; the transaction stays open.

@param value the document
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns the `_id`, and `Inserted` true
@exception Lwnrdb.Client.Errors.ConflictException with code `409-18` when a document with that `_id` already exists

## Query()
Starts a fluent query over the collection, sent on the transaction's connection.

@returns an empty pipeline

## SaveAllAsync(IEnumerable<T>, CancellationToken)
Inserts or replaces many documents as part of the transaction.

@param values the documents; two with the same `_id` are refused with `400-3`
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns the inserted and updated ids

## SaveAsync(T, CancellationToken)
Inserts or replaces one document as part of the transaction.

@param value the document
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns the `_id` and whether it was an insert

## UpdateWhereAsync(Filter, object, CancellationToken)
Updates every document the filter matches in the transaction's view, its own writes included, as part of the
transaction, by merging the patch's top-level fields into it. The patch is mapped as in
`Collection<T>.UpdateWhereAsync`.

@param condition the documents to update, built like a query filter
@param patch the fields to set on every matching document
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns how many documents matched and were buffered
@exception Lwnrdb.Client.Errors.MappingException when the patch does not map to a JSON object

## UpdateWhereAsync(Expression<Func<T, bool>>, object, CancellationToken)
Updates every document the predicate matches as part of the transaction, translated to a server filter as `Where` is.

@param predicate the documents to update, over the stored members of `T`
@param patch the fields to set on every matching document
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns how many documents matched and were buffered
@exception System.NotSupportedException when the predicate cannot run on the server
@exception Lwnrdb.Client.Errors.MappingException when the patch does not map to a JSON object

## DatabaseName
The database the collection belongs to.

## Name
The collection's name.
