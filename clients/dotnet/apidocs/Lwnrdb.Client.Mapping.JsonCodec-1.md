Maps `T` to and from documents with `System.Text.Json`. It is the codec a typed collection uses unless one is passed to `Database.Collection<T>`.

The options come from `LwnrdbJson.CreateOptions`: a member named `Id` is stored as `_id`, `DateTime`, `TimeOnly`, `GeoPoint` and `Vector` map to the custom value types, and `double[]` or `float[]` members marked `[LwnrdbVector]` map to `#vector`. Non-finite numbers, integers beyond 2^53 and text that looks like a custom value are refused with a `MappingException`.

@typeparam T the mapped type, which must serialize to a JSON object

## JsonCodec(JsonSerializerOptions)
Creates a codec.

@param options the options to map with, usually from `LwnrdbJson.CreateOptions`
@exception System.ArgumentException when `T` does not map to a JSON object, or its `_id` member is not a string

## Decode(Document)
Maps a document to `T`.

@param document the stored document
@returns the value
@exception Lwnrdb.Client.Errors.MappingException when the document does not map to `T`

## Encode(T)
Maps a value to a document.

@param value the value
@returns the document
@exception Lwnrdb.Client.Errors.MappingException when the value does not map to a document, or holds a number or text the server cannot store faithfully
