# TransactionCollection

A collection seen through a transaction: every call runs on the transaction's connection.

## databaseName

The database the collection belongs to.

## name

The collection's name.

## save

Buffers an insert or replace of one document.

@param value the document
@returns the `_id` and whether it was an insert

## saveAll

Buffers inserts or replaces of many documents.

@param values the documents
@returns the inserted and updated ids

## findById

Reads one document, seeing the transaction's own writes.

@param id the `_id`
@returns the document, or `undefined` when there is none

## delete

Buffers the delete of one document.

@param id the `_id`

## deleteWhere

Buffers the delete of every document the filter matches in this transaction's view, its own writes included.

@param condition the documents to delete, built like a query filter
@returns how many documents matched and were buffered

## updateWhere

Buffers the update of every document the filter matches in this transaction's view, its own writes included, each
merged with the patch's top-level fields.

@param condition the documents to update, built like a query filter
@param patch the top-level fields to set on every match
@returns how many documents matched and were buffered

## query

@returns an empty pipeline that reads through the transaction
