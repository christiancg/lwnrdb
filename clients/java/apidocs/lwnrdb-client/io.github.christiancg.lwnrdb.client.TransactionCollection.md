A collection seen from inside a transaction.

@param <T> the type documents are decoded to

## databaseName()

@return the database the collection belongs to

## name()

@return the collection's name

## save(T)

Buffers an insert or replace until commit.

@param value the document
@return the `_id` and whether it is an insert

## insert(T)

Buffers an insert until commit. A document whose `_id` this transaction's view already holds, its own writes included,
is refused with `409-18` and nothing is buffered; the transaction stays open and its other writes still commit.

@param value the document
@return the `_id`; the reply is always an insert

## saveAll(List)

Buffers many writes until commit.

@param values the documents
@return the inserted and updated ids

## findById(String)

Reads one document, seeing this transaction's own writes.

@param id the `_id`
@return the document, empty when there is none

## delete(String)

Buffers a delete until commit.

@param id the `_id`

## deleteWhere(Filter)

Buffers a delete of every document the filter matches in this transaction's view, its own writes included, until
commit.

@param condition the documents to delete, built like a query filter
@return how many documents matched and were buffered

## updateWhere(Filter, Object)

Buffers an update of every document the filter matches in this transaction's view, its own writes included, until
commit. Each match gets the patch's top-level fields merged in; every other field is kept.

@param condition the documents to update, built like a query filter
@param patch the fields to set: a `Document`, a `Map` or an object mapped like a saved document
@return how many documents matched and were buffered

## query()

@return a query that sees this transaction's own writes
