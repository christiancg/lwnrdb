# AsyncTransaction

A transaction pinned to one connection. Every request it sends goes to that connection.

An unknown commit outcome (`500-33`, `500-34`) or a lost connection discards the connection without a
rollback, since the server may have committed.

The sync class `Transaction` has the same members; its methods block and return the value directly.

## id

The server's transaction id.

## is_open

Whether the transaction has not been committed or rolled back yet.

## collection

Returns a collection inside this transaction, typed like `Database.collection`.

Raises:
    LwnrdbError: the transaction already ended

## commit

Commits the transaction.

Raises:
    UnknownOutcomeError: the server could not say whether the commit applied

## rollback

Rolls the transaction back.

## close

Rolls the transaction back when it is still open.

## __aenter__

Returns the transaction itself.

## __aexit__

Rolls the transaction back when it is still open.
