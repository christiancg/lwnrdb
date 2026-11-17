# Query

An immutable aggregation pipeline over one collection. Every step method returns a new query, so a base
query can be shared and extended.

```ts
const page = await orders
    .query()
    .filter(and(field("status").eq("active"), field("total").gt(100)))
    .sort("createdAt", Order.DESC)
    .skip(20)
    .limit(10)
    .toArray();
```

The result order is defined only when the last order-determining step is a `sort`.

## steps

The pipeline's steps, in order.

## isDirtyRead

Whether the query skips the collection's read lock.

## filter

Keeps the rows the filter matches. A leading filter on an indexed field is answered from the index.

@param filter the condition
@returns the extended query

## map

Sets or removes fields on every row.

@param operations `MapOps.set` and `MapOps.remove` operations, each optionally limited with `.when(filter)`
@returns the extended query

## groupBy

Groups the rows by a field's value. Each output row holds the value and the group's rows.

@param field the field path
@returns the extended query

## join

Adds, to each row, the rows of another collection whose `remoteField` equals the row's `localField`.

@param collection the other collection, in the same database
@param localField the field of this collection's rows
@param remoteField the field of the other collection's rows
@param asField where the matches are stored
@returns the extended query

## distinct

Keeps distinct rows, or the distinct values of one field.

@param field optional field path
@returns the extended query

## skip

Skips rows.

@param skip how many, zero or more
@returns the extended query

## limit

Keeps at most a number of rows.

@param limit how many, at least one
@returns the extended query

## sort

Sorts the rows by one field.

@param field the field path
@param order `Order.ASC` (the default) or `Order.DESC`
@returns the extended query

## reduce

Folds the rows into one value with a SimpleJS script.

@param script the reducer's source
@param seed optional initial value and the field that holds the result
@returns the extended query

## dirtyRead

@returns the same pipeline, run without the collection's read lock

## toArray

Runs the query and decodes every row.

@returns the rows; an empty result is an empty array

## toDocuments

Runs the query and returns the raw rows, for steps whose rows have another shape, such as `groupBy`.

@returns the rows

## first

@returns the first row, or `undefined` when there is none

## count

@returns how many rows the pipeline yields

## analyze

Runs the query with the server's analysis: which indexes it used and how many documents it read.

@returns the rows and the analysis
