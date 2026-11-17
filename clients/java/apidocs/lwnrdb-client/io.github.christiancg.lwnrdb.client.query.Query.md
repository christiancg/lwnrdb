An immutable aggregation pipeline over one collection. Every step method returns a new query, so a
base query can be shared and extended.

```java
List<Order> page = orders.query()
        .filter(and(field("status").eq("active"), field("total").gt(100)))
        .sort("createdAt", Order.DESC)
        .skip(20)
        .limit(10)
        .toList();
```

The result order is defined only when the last order-determining step is a `sort`.

@param <T> the type rows are decoded to

## Query(QueryTarget)

Internal: use `Collection.query()`.

@param target where the query runs

## filter(Filter)

Keeps the rows the filter matches. A leading filter on an indexed field is answered from the index.

@param filter the condition
@return the extended query

## map(MapOperation...)

Adds, computes or removes fields on every row.

@param operations `MapOps.set` and `MapOps.remove` calls
@return the extended query

## groupBy(String)

Groups rows by a field: each output row holds the key and a `group` array.

@param field the grouping field
@return the extended query

## join(String, String, String, String)

Adds the matching rows of another collection as an array.

@param collection the other collection, in the same database
@param localField the field of this collection's rows
@param remoteField the field of the other collection's rows
@param asField where the matches are stored
@return the extended query

## distinct()

@return the query with whole-row duplicates removed

## distinct(String)

@param field the field whose distinct values are kept
@return the extended query

## skip(int)

@param skip rows to drop, zero or more
@return the extended query

## limit(int)

@param limit rows to keep, at least 1
@return the extended query

## sort(String, Order)

@param field the sort field; values sort by type, then by value
@param order ascending or descending
@return the extended query

## reduce(String)

Folds all rows into one with a SimpleJS `(accumulator, doc) => accumulator` function, starting from `null`.

@param script the module whose default export is the fold
@return the extended query

## reduce(String, Object, String)

@param script the module whose default export is the fold
@param initialValue the starting accumulator
@param resultField where the result is stored, `value` when `null`
@return the extended query

## dirtyRead()

@return the query reading without the collection's read lock

## toList()

Runs the query.

@return the rows, decoded by the collection's codec

## toDocuments()

Runs the query without decoding, for pipelines whose rows are not the collection's type (`groupBy`, `count`).

@return the rows

## first()

@return the first row of the query limited to one, empty when there is none

## count()

@return the number of rows the query produces

## analyze()

Runs the query with `"analyze": true`.

@return the rows and the server's explanation

## steps()

@return the pipeline as it will be sent

## isDirtyRead()

@return `true` when the query skips the collection's read lock
