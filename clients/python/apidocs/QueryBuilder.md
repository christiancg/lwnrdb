# QueryBuilder

The immutable step list behind `Query` and `AsyncQuery`. Every step returns a new query, so a query can be
reused and extended.

## steps

The pipeline steps, in order.

## is_dirty_read

Whether the query reads without waiting for writers.

## filter

Keeps the documents that match a filter.

```python
query.filter(field("status").eq("active") & (field("total") > 100))
```

## map

Sets or removes fields on every document.

Args:
    operations: from `MapOps.set` and `MapOps.remove`

## group_by

Groups the documents by a field's value.

## join

Joins the documents of another collection of the same database whose `remote_field` equals this
document's `local_field`, as a list under `as_field`.

## distinct

Keeps one document per distinct value of a field, or distinct whole documents without one.

## skip

Skips the first documents.

Args:
    skip: how many, at least 0

## limit

Keeps at most this many documents.

Args:
    limit: at least 1

## sort

Sorts by one field.

Args:
    field: the field
    order: `Order.ASC` (the default) or `Order.DESC`

## reduce

Folds the documents into one with a script.

Args:
    script: a SimpleJS module whose default export takes the accumulator and a document
    initial_value: the accumulator's start; the server's default when omitted (`None` is a value)
    result_field: the field of the result document that holds the value

## dirty_read

Returns the same query reading without waiting for writers holding the collection's lock.
