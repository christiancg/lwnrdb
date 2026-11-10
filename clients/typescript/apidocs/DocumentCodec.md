# DocumentCodec

Maps your type to stored documents and back. Pass one to `Database.collection(name, codec)`.

## encode

@param value your value
@returns the document to store

## decode

@param document a stored document, custom values decoded
@returns your value
