A `DocumentCodec` that maps POJOs and records through Jackson 3, with the client's module for the custom
types. A property named `id` is stored as `_id` unless the type also declares `_id`.

@param <T> the mapped type

## JacksonCodec(Class, Json)

Internal: use `Database.collection(name, Class)`.

@param type the mapped class
@param json the client's JSON configuration
