# AsyncIndexes

The single-field indexes of a collection.

The sync class `Indexes` has the same members; its methods block and return the value directly.

## create

Creates an index on each named field. Fields already indexed are left as they are.

Args:
    field: the first field
    more_fields: more fields, built in the same pass

## drop

Drops the index on a field.

## reindex

Rebuilds the field indexes from the documents.

Args:
    fields: the fields to rebuild; every indexed field when none is named

## reindex_primary_key

Rebuilds the primary-key index from the page files, then the field indexes that need it.
