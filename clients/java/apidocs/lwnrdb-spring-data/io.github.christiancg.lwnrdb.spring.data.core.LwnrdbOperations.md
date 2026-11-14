Spring Data operations on LWNRDB entities. Inside a Spring transaction every operation runs in that
transaction, on its connection. Failures are Spring's `DataAccessException`s.

## save(T)

@param entity the entity; a `null` id is replaced by a UUID
@param <T> the entity type
@return the stored entity, with its id

## saveAll(Iterable)

@param entities entities of one class, saved in one request
@param <T> the entity type
@return the stored entities, with their ids

## findById(String, Class)

@param id the id
@param type the entity class
@param <T> the entity type
@return the entity, empty when there is none

## existsById(String, Class)

@param id the id
@param type the entity class
@param <T> the entity type
@return whether it exists

## findAll(Class)

@param type the entity class
@param <T> the entity type
@return every entity

## findAll(Class, Sort)

@param type the entity class
@param sort one property at most; LWNRDB sorts by one
@param <T> the entity type
@return every entity, sorted

## findAllById(Iterable, Class)

@param ids the ids
@param type the entity class
@param <T> the entity type
@return the entities that exist

## find(Class, LwnrdbQuerySpec)

@param type the entity class
@param spec filter, sort, skip and limit
@param <T> the entity type
@return the matching entities

## count(Class)

@param type the entity class
@param <T> the entity type
@return the number of entities

## count(Class, Filter)

@param type the entity class
@param filter the filter, or null for all
@param <T> the entity type
@return the number of matching entities

## exists(Class, Filter)

@param type the entity class
@param filter the filter, or null for any
@param <T> the entity type
@return whether one matches

## deleteById(String, Class)

Deletes one entity; a missing id is ignored.

@param id the id
@param type the entity class
@param <T> the entity type

## delete(T)

@param entity an entity with an id
@param <T> the entity type

## deleteAllById(Iterable, Class)

@param ids the ids; one request each
@param type the entity class
@param <T> the entity type

## deleteAll(Class)

Deletes every entity with one conditional `DELETE`, all or nothing. A collection holding more than the server's
`deleteMaxMatches` documents is refused with `400-44`; it then reads every id and deletes one at a time.

@param type the entity class
@param <T> the entity type
@return how many were deleted

## delete(Class, LwnrdbQuerySpec)

Reads the matching entities, then deletes them one at a time, because the server does not return what a conditional
`DELETE` removed.

@param type the entity class
@param spec what to delete
@param <T> the entity type
@return the deleted entities

## deleteWhere(Class, Filter)

Deletes every entity the filter matches with one conditional `DELETE`, all or nothing. Past the server's
`deleteMaxMatches` it fails with the translated `400-44` and deletes nothing.

@param type the entity class
@param filter the filter, or null for every entity
@param <T> the entity type
@return how many were deleted

## deleteMatching(Class, LwnrdbQuerySpec)

Deletes what the spec matches and counts it. A spec with only a filter is one conditional `DELETE`, which falls back
to reading and deleting one at a time when the server refuses it with `400-44`; a spec with a sort, a skip or a limit
always reads first.

@param type the entity class
@param spec what to delete
@param <T> the entity type
@return how many were deleted

## updateWhere(Class, Filter, Object)

Updates every entity the filter matches with one conditional `SAVE`, all or nothing, merging the patch's top-level
fields into each match and keeping the others. The patch's fields are stored names, not property names: translate a
property with `wirePath`. Past the server's `updateMaxMatches` it fails with the translated `400-45` and updates
nothing.

@param type the entity class
@param filter the filter, or null for every entity
@param patch the fields to set: a `Document`, a `Map` or an object the client maps like a saved document
@param <T> the entity type
@return how many were updated

## query(Class)

The client's fluent query, for what derived queries cannot express: vector `nearest`, geo `distance`, scripts, `map`.

@param type the entity class
@param <T> the entity type
@return an empty pipeline over the entity's collection

## collection(Class)

@param type the entity class
@param <T> the entity type
@return the client's typed collection, outside any transaction

## execute(Function)

@param action work with the client
@param <R> the result type
@return the action's result

## wirePath(Class, String)

@param type the entity class
@param propertyPath a dotted Java property path
@return the stored field path

## getMappingContext()

@return the mapping metadata

## getClient()

@return the client
