The field indexes of one collection. Obtained from `Collection<T>.Indexes()`.

An index covers one field; a query can combine several. Index creation and dropping are synchronous and idempotent.

## CreateAsync(IReadOnlyList<string>, CancellationToken)
Creates indexes on several fields in one request. Fields that already have an index are left as they are.

@param fields the field names, at least one
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception System.ArgumentException when `fields` is empty

## CreateAsync(string, string[])
Creates indexes on one or more fields in one request.

This overload has no cancellation token; use the list overload to pass one.

@param field the first field name
@param moreFields further field names
@returns a task that completes when the indexes exist

## DropAsync(string, CancellationToken)
Drops the index on a field.

@param field the field name
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@exception Lwnrdb.Client.Errors.NotFoundException with code `404-6` when the field has no index

## ReindexAsync(IReadOnlyList<string>, CancellationToken)
Rebuilds field indexes from the stored documents.

@param fields the fields to rebuild; empty rebuilds every index of the collection
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the rebuilt fields

## ReindexAsync(string[])
Rebuilds field indexes from the stored documents.

This overload has no cancellation token; use the list overload to pass one.

@param fields the fields to rebuild; none rebuilds every index of the collection
@returns the rebuilt fields

## ReindexPrimaryKeyAsync(CancellationToken)
Rebuilds the primary-key index from the collection's page files, then the field indexes.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the number of primary-key entries and the entries that carried no version
@exception Lwnrdb.Client.Errors.ConflictException with code `409-15` when the primary-key index cannot be rebuilt from the page files
