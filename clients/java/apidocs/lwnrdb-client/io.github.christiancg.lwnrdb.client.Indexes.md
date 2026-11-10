The single-field indexes of one collection. Index maintenance runs in the background on the server, yet
index-backed reads always answer as a scan would.

## create(String, String...)

Creates one index per field in a single request. Fields that are already indexed are skipped.

@param field a field name, dotted for a nested field
@param moreFields further fields

## drop(String)

@param field the indexed field

## reindex(String...)

Rebuilds field indexes from the stored documents.

@param fields the fields to rebuild; none rebuilds every index
@return the rebuilt fields

## reindexPrimaryKey()

Rebuilds the primary-key index from the page files, then the field indexes that need it.

@return the rebuilt fields and the primary-key counts
