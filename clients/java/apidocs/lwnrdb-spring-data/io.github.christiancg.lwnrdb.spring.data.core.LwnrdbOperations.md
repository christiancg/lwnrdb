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

Reads every id, then deletes one at a time: the protocol has no bulk delete.

@param type the entity class
@param <T> the entity type
@return how many were deleted

## delete(Class, LwnrdbQuerySpec)

@param type the entity class
@param spec what to delete
@param <T> the entity type
@return the deleted entities

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
