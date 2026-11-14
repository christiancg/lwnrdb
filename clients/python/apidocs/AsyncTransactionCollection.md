# AsyncTransactionCollection

One collection seen from inside a transaction. Its reads see the transaction's own writes.

The sync class `TransactionCollection` has the same members; its methods block and return the value directly.

## database_name

The database the collection belongs to.

## name

The collection's name.

## save

Buffers an insert or replace of one document.

## save_all

Buffers an insert or replace of many documents.

## find_by_id

Reads one document by its `_id`, seeing the transaction's own writes.

## update_where

Buffers the merge of `patch` into every document the filter matches in the transaction's view, its own writes
included, and returns how many matched.

## delete

Buffers the delete of one document.

## delete_where

Buffers the delete of every document the filter matches in the transaction's view, its own writes included, and
returns how many matched.

## query

Starts a query that sees the transaction's own writes.
