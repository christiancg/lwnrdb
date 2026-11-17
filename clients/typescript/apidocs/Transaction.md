# Transaction

A server transaction, pinned to one connection from its start to its commit or rollback.

Its writes take their collections' write locks, so a read of those collections from another connection waits for
the commit. The server rolls back a transaction idle for longer than `transactionIdleTimeoutMs`; its next request then
fails with `409-16`. `await using` rolls back a transaction still open at the end of the block.

## id

The server's id for the transaction.

## start

Internal: use `LwnrdbClient.beginTransaction()` or `LwnrdbClient.transaction(body)`.

@param lease the pooled connection the transaction keeps
@param parent the client's context
@returns the open transaction

## isOpen

Whether the transaction is neither committed nor rolled back.

## collection

Names a collection whose reads and writes belong to this transaction.

@param database the database name
@param collection the collection name
@param codec optional mapping between your type and stored documents
@returns the collection
@throws LwnrdbError when the transaction already ended

## commit

Commits the transaction and returns its connection to the pool. An unknown outcome (`500-33`, `500-34`) or a
lost connection discards the connection instead.

## rollback

Rolls the transaction back and returns its connection to the pool.

## close

Rolls the transaction back when it is still open.

## Symbol.asyncDispose

Rolls the transaction back when it is still open, for `await using`.
