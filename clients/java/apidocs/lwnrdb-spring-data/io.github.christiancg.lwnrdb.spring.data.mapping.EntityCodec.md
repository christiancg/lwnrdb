Maps an entity to a document through the client's Jackson codec, then stores the id property as `_id`
and drops `@Transient` fields.

@param <T> the entity type

## EntityCodec(DocumentCodec, LwnrdbPersistentEntity)

@param jackson the client's codec for the type
@param entity the type's metadata

## idOf(T)

@param value an entity
@return its id, empty when it has none yet

## withGeneratedId(T)

@param value an entity
@return the same entity when it has an id, else a copy with a random UUID as id
