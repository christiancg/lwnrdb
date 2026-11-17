The JSON Schema (draft 2020-12) that every write to one collection must satisfy.

## save(Document)

@param schema the schema
@return the server's warnings about keywords it does not enforce

## get()

@return the schema, empty when the collection has none

## delete()

Removes the schema.
