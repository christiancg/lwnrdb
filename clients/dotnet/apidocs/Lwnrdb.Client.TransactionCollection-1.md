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

## FindByIdAsync(string, CancellationToken)
Reads one document by its exact, case-sensitive `_id`, seeing the transaction's own writes.

@param id the document's `_id`
@param ct cancels the wait; a request already sent closes the transaction's connection, since its outcome is unknown
@returns the document, or the default value of `T` when there is none

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

## DatabaseName
The database the collection belongs to.

## Name
The collection's name.
