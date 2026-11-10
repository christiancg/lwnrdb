An immutable JSON object as stored by the server.

Values are converted when a document is built: `GeoPoint`, `Vector`, `LocalDateTime` and `LocalTime` become the
server's `#geo(...)`, `#vector(...)`, `#datetime(...)` and `#time(...)` values. A plain string that matches the
server's custom-value pattern (`#name(...)`) is refused, because the server would read it as a custom value.

Getters take a dotted path (`address.city`) and return empty for a missing or `null` value. A value of another
type throws `MappingException`.

## ID_FIELD

The primary-key field, `_id`.

## empty()

@return a document with no fields

## of(Object...)

Builds a document from field/value pairs: `Document.of("_id", "o-1", "total", 120)`.

@param fieldsAndValues alternating field names and values
@return the document
@throws IllegalArgumentException when the arguments are not pairs, or a value cannot be stored

## from(Map)

@param fields the document's fields
@return the document

## parse(String)

@param json a JSON object
@return the document
@throws MappingException when the text is not a JSON object

## with(String, Object)

@param field the top-level field to set
@param value its new value
@return a copy with the field set

## without(String)

@param field the top-level field to remove
@return a copy without the field

## id()

@return the `_id`, empty for a document that has none yet

## has(String)

@param path a dotted path
@return `true` when the path holds a non-null value

## fieldNames()

@return the top-level field names, in document order

## get(String)

@param path a dotted path
@return the value as plain Java (`Map`, `List`, `String`, `Number`, `Boolean`)

## getString(String)

@param path a dotted path
@return the string

## getDouble(String)

@param path a dotted path
@return the number

## getLong(String)

@param path a dotted path
@return the number, which must be a whole number

## getBoolean(String)

@param path a dotted path
@return the boolean

## getDateTime(String)

@param path a dotted path holding a `#datetime(...)` value
@return the date-time

## getTime(String)

@param path a dotted path holding a `#time(...)` value
@return the time

## getInstant(String, ZoneId)

@param path a dotted path holding a `#datetime(...)` value
@param zone the zone the stored date-time is in
@return the instant

## getGeo(String)

@param path a dotted path holding a `#geo(...)` value
@return the point

## getVector(String)

@param path a dotted path holding a `#vector(...)` value
@return the vector

## getDocument(String)

@param path a dotted path holding an object
@return the nested document

## getList(String)

@param path a dotted path holding an array
@return the items as plain Java

## toMap()

@return the document as nested `Map`s and `List`s

## toJson()

@return the document as one line of JSON, exactly as it is sent
