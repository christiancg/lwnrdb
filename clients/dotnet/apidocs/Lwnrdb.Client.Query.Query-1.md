A fluent query: an immutable pipeline of steps over one collection. Obtained from `Collection<T>.Query()`.

Each method returns a new query with one more step, so a query can be kept and extended. The steps run on the server in order, as one `AGGREGATE` request, when a terminal method (`ToListAsync`, `ToDocumentsAsync`, `FirstOrDefaultAsync`, `CountAsync`, `AnalyzeAsync`) is awaited. A step that follows `Map`, `GroupBy`, `Join` or `Reduce` sees the rows that step produced, which can differ from the stored documents; `ToListAsync` then fails with a `MappingException` unless the rows still map to `T`, so use `ToDocumentsAsync` for them.

@typeparam T the type rows are decoded to

## AnalyzeAsync(CancellationToken)
Runs the query with `analyze` set and returns the rows with the server's analysis of how it ran.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the rows as documents and the analysis

## CountAsync(CancellationToken)
Counts the rows the pipeline produces, with a final `COUNT` step.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the number of rows

## DirtyRead()
Reads without waiting for the collection's read lock. The read may see a write that is still being applied.

@returns the new query

## Distinct(string)
Adds a `DISTINCT` step.

@param field the field whose distinct values are kept, or null to remove duplicate rows
@returns the new query

## Filter(Filter)
Adds a `FILTER` step.

@param filter the condition; build it with `Filters`
@returns the new query

## FirstOrDefaultAsync(CancellationToken)
Runs the query with a final `LIMIT 1`.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the first row, or the default value of `T` when there is none

## GroupBy(string)
Adds a `GROUP_BY` step. Each output row holds the group's key and its rows in a `group` array.

@param field the field to group by
@returns the new query

## Join(string, string, string, string)
Adds a `JOIN` step against another collection of the same database. Each row gets the matching remote documents as an array under `asField`.

@param collection the remote collection
@param localField the field of this collection
@param remoteField the field of the remote collection that must equal it
@param asField the field that receives the list of matches
@returns the new query

## Limit(int)
Adds a `LIMIT` step.

@param limit the maximum number of rows, at least 1
@returns the new query
@exception System.ArgumentOutOfRangeException when the limit is not positive

## Map(MapOperation[])
Adds a `MAP` step that sets or removes fields on every row.

@param operations the operations, at least one; build them with `MapOps`
@returns the new query
@exception System.ArgumentException when no operation is given

## Reduce(string)
Adds a `REDUCE` step that folds all rows into one with a script, starting from null. The script's default export is `(accumulator, doc) => accumulator`.

@param script the script source
@returns the new query

## Reduce(string, object, string)
Adds a `REDUCE` step that folds the rows with a script, starting from an initial value.

@param script the script source
@param initialValue the accumulator's initial value, encoded as in `Document.Of`
@param resultField the field that receives the result, or null for `value`
@returns the new query

## Skip(int)
Adds a `SKIP` step.

@param skip the number of rows to drop, not negative
@returns the new query
@exception System.ArgumentOutOfRangeException when the number is negative

## Sort(string, Order)
Adds a `SORT` step. The server sorts by one field.

@param field the field to sort by
@param order ascending or descending; ascending by default
@returns the new query

## ToDocumentsAsync(CancellationToken)
Runs the query and returns the rows as documents, without mapping them to `T`.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the rows; empty when nothing matches

## ToListAsync(CancellationToken)
Runs the query and maps the rows to `T`.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the rows; empty when nothing matches
@exception Lwnrdb.Client.Errors.MappingException when a row does not map to `T`

## IsDirtyRead
Whether the query reads without the collection's read lock.

## Steps
The pipeline steps added so far, in order.
