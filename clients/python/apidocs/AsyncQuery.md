# AsyncQuery

A query over one collection: the steps of `QueryBuilder` plus the async terminal operations.

The sync class `Query` has the same members; its methods block and return the value directly.

## to_list

Runs the query and decodes every row through the collection's codec.

## to_documents

Runs the query and returns the rows as documents.

## first

Runs the query with `LIMIT 1` and returns the row, or `None`.

## count

Runs the query followed by a `COUNT` step and returns the count.

## analyze

Runs the query with analysis and returns the rows and how the server answered them.
