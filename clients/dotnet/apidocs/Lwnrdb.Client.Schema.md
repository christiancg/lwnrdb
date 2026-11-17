The JSON Schema of one collection. Obtained from `Collection<T>.Schema()`.

A collection has at most one schema (draft 2020-12, a supported keyword subset). Once saved, every write is validated against it.

## DeleteAsync(CancellationToken)
Removes the collection's schema.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown

## GetAsync(CancellationToken)
Reads the collection's schema.

@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the schema, or null when the collection has none

## SaveAsync(Document, CancellationToken)
Saves the schema, replacing any previous one.

@param schema the JSON Schema document
@param ct cancels the wait; a request already sent closes its connection, since its outcome is unknown
@returns the warnings the server reported, for example about existing documents that do not comply
@exception Lwnrdb.Client.Errors.LwnrdbException with code `400-8` when the schema is not valid
