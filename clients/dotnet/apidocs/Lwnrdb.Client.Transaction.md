A transaction pinned to one connection. Obtained from `LwnrdbClient.BeginTransactionAsync`, or managed for you by `LwnrdbClient.TransactionAsync`.

Writes are buffered by the server and applied together on `CommitAsync`. Disposing a transaction that is still open rolls it back. Operations that the server does not allow inside a transaction, such as DDL, are refused locally with a `LwnrdbException`.

## Collection(string, string)
Returns a collection whose documents are `Document` values, bound to this transaction.

@param database the database name
@param collection the collection name
@returns the collection handle
@exception System.InvalidOperationException when the transaction is already committed or rolled back

## Collection<T>(string, string, IDocumentCodec<T>)
Returns a collection whose documents are mapped to `T`, bound to this transaction.

@typeparam T the type documents are mapped to
@param database the database name
@param collection the collection name
@param codec how `T` maps to and from a document; when null, a `JsonCodec<T>` over `ClientSettings.Json`
@returns the collection handle
@exception System.InvalidOperationException when the transaction is already committed or rolled back

## CommitAsync(CancellationToken)
Commits the transaction and releases its connection.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception System.InvalidOperationException when the transaction is already committed or rolled back
@exception Lwnrdb.Client.Errors.UnknownOutcomeException with code `500-33` or `500-34` when the commit passed its commit point but could not finish applying
@exception Lwnrdb.Client.Errors.ConflictException when the server aborted the transaction, for example `409-5`, `409-7` or `409-16`

## DisposeAsync()
Rolls the transaction back when it is still open, and releases its connection. Does nothing after a commit or rollback.

@returns a task that completes when the transaction is closed

## RollbackAsync(CancellationToken)
Discards the buffered writes and releases the connection.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception System.InvalidOperationException when the transaction is already committed or rolled back

## Id
The id the server gave the transaction.

## IsOpen
Whether the transaction can still be used: true until `CommitAsync` or `RollbackAsync` is called.
