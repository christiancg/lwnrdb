The outcome of a reindex.

@param RebuiltFields the fields whose indexes were rebuilt
@param PrimaryKeyEntries the number of primary-key entries rebuilt, or null when the primary key was not rebuilt
@param UnversionedEntries the number of entries that carried no write version, or null when the primary key was not rebuilt

## ReindexResult(IReadOnlyList<string>, long?, long?)
Creates a result.

@param RebuiltFields the fields whose indexes were rebuilt
@param PrimaryKeyEntries the number of primary-key entries rebuilt, or null
@param UnversionedEntries the number of entries without a write version, or null

## PrimaryKeyEntries
The number of primary-key entries rebuilt, or null when the primary key was not rebuilt.

## RebuiltFields
The fields whose indexes were rebuilt.

## UnversionedEntries
The number of entries that carried no write version, or null when the primary key was not rebuilt.
