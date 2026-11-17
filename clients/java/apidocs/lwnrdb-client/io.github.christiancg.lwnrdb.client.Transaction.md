An open transaction. Its writes are buffered on the server and applied together at `commit()`; its own
reads see them. Only `save`, `saveAll`, `delete`, `findById` and queries are available inside it.

A transaction holds one connection. An idle one is rolled back by the server after `transactionIdleTimeoutMs`
(30 s by default), and its next request fails with `409-16`.

## id()

@return the server's transaction id

## isOpen()

@return `true` until commit or rollback

## collection(String, String)

@param database the database
@param collection the collection
@return the collection inside this transaction

## collection(String, String, Class)

@param database the database
@param collection the collection
@param type the class documents map to
@param <T> the mapped type
@return the typed collection inside this transaction

## collection(String, String, DocumentCodec)

@param database the database
@param collection the collection
@param codec maps between documents and `T`
@param <T> the mapped type
@return the typed collection inside this transaction

## commit()

Applies every buffered write and releases the connection.

@throws io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException when the commit may or may not have applied

## rollback()

Discards every buffered write and releases the connection.

## close()

Rolls back when the transaction is still open.
