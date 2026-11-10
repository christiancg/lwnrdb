# Schema

The JSON Schema (draft 2020-12) a collection validates every write against.

## save

Replaces the collection's schema.

@param schema the schema document
@returns the server's warnings about keywords it does not enforce

## get

@returns the schema, or `undefined` when the collection has none

## delete

Removes the collection's schema.
