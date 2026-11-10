Maps between stored documents and an application type. `Database.collection(name, Class)` uses a
Jackson-based codec; implement this interface to map differently.

@param <T> the application type

## encode(T)

@param value the application value
@return the document to store

## decode(Document)

@param document a stored document
@return the application value

## identity()

@return the codec of `Collection<Document>`, which maps nothing
