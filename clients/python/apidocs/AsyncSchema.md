# AsyncSchema

The JSON Schema (draft 2020-12) a collection validates every write against.

The sync class `Schema` has the same members; its methods block and return the value directly.

## save

Saves the collection's schema.

Returns:
    the server's warnings about the schema

## get

Returns the collection's schema, or `None` when it has none.

## delete

Deletes the collection's schema.
