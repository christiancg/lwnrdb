# Indexes

The single-field indexes of one collection. A query can use several of them at once.

## create

Creates one index per field in one request. Fields already indexed are left as they are.

@param field the first field path
@param moreFields further field paths

## drop

Drops the index on a field.

@param field the field path

## reindex

Rebuilds field indexes from the stored documents.

@param fields the fields to rebuild; none rebuilds them all
@returns the rebuilt fields

## reindexPrimaryKey

Rebuilds the primary-key index from the page files, then the field indexes that need it.

@returns the rebuilt fields and the primary-key entry counts
