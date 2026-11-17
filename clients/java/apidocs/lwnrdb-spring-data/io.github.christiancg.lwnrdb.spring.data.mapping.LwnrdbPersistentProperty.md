The metadata of one stored property.

## ID_ALIAS

The property name treated as the id when no property is annotated with `@Id`.

## LwnrdbPersistentProperty(Property, PersistentEntity, SimpleTypeHolder)

@param property the Java property
@param owner its entity
@param simpleTypeHolder the types stored as values

## wireName()

@return the field name on the wire: `_id` for the id, else the `@JsonProperty` name or the Java name

## javaWireName()

@return the `@JsonProperty` name or the Java name, ignoring the id rename
