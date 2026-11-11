Maps a type to and from a stored document. Implement it to control the mapping instead of using `JsonCodec<T>`.

@typeparam T the mapped type

## Decode(Document)
Maps a stored document to a value.

@param document the stored document
@returns the value
@exception Lwnrdb.Client.Errors.MappingException when the document does not map to `T`

## Encode(T)
Maps a value to a document to store.

@param value the value
@returns the document
@exception Lwnrdb.Client.Errors.MappingException when the value does not map to a document
