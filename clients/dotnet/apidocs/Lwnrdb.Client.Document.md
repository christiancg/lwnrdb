A schema-less JSON document: an immutable object with typed getters.

Fields are addressed by dotted paths such as `address.city`. Custom values (`#datetime`, `#time`, `#geo`, `#vector`) are held as tagged strings and parsed by the typed getters, so `GetDateTime`, `GetTimeOnly`, `GetGeoPoint` and `GetVector` return them as `DateTime`, `TimeOnly`, `GeoPoint` and `Vector`. `With` and `Without` return a new document and never change the original.

## IdField
The name of the primary-key field, `_id`.

## From(IEnumerable<KeyValuePair<string, object>>)
Creates a document from field and value pairs. Values are encoded as in `Of`.

@param fields the field names and values
@returns the document
@exception Lwnrdb.Client.Errors.MappingException when a value does not map to JSON, such as a non-finite number

## Get(string)
Reads the value at a path as a plain .NET value.

@param path the dotted field path
@returns a `Document` for an object, a list for an array, a string, a double, a boolean, a custom value (`DateTime`, `TimeOnly`, `GeoPoint`, `Vector`), or null when the field is missing or null

## GetBoolean(string)
Reads a boolean field.

@param path the dotted field path
@returns the value, or null when the field is missing or not a boolean

## GetDateTime(string)
Reads a `#datetime` field. The value has `DateTimeKind.Unspecified`.

@param path the dotted field path
@returns the value, or null when the field is missing or not a date-time

## GetDocument(string)
Reads an object field.

@param path the dotted field path
@returns a copy of the nested object, or null when the field is missing or not an object

## GetDouble(string)
Reads a numeric field. Every number is stored as a double.

@param path the dotted field path
@returns the value, or null when the field is missing or not a number

## GetGeoPoint(string)
Reads a `#geo` field.

@param path the dotted field path
@returns the value, or null when the field is missing or not a geo point

## GetList(string)
Reads an array field.

@param path the dotted field path
@returns the elements as plain .NET values, as `Get` returns them, or null when the field is missing or not an array

## GetLong(string)
Reads an integral numeric field.

@param path the dotted field path
@returns the value, or null when the field is missing, not a number or not a whole number

## GetString(string)
Reads a string field. A custom value is a string on the wire but is not returned here as text unless it is one.

@param path the dotted field path
@returns the value, or null when the field is missing or not a string

## GetTimeOnly(string)
Reads a `#time` field.

@param path the dotted field path
@returns the value, or null when the field is missing or not a time

## GetVector(string)
Reads a `#vector` field.

@param path the dotted field path
@returns the value, or null when the field is missing or not a vector

## Has(string)
Tells whether a field exists, a field holding null included.

@param path the dotted field path
@returns true when the field exists

## Of(object[])
Creates a document from alternating field names and values.

Strings, booleans, numbers, `DateTime`, `TimeOnly`, `GeoPoint`, `Vector`, `Document`, dictionaries and collections are accepted; any other object is serialized with the default LWNRDB JSON options. Integers beyond 2^53 and non-finite numbers are refused, and so is plain text that looks like a custom value. A `DateTime` of kind `Utc` or `Local` and a `DateTimeOffset` are refused, since a document has no time zone setting; use `DateTimeKind.Unspecified`.

@param fieldsAndValues alternating field names and values: `Of("name", "Ana", "age", 31)`
@returns the document
@exception System.ArgumentException when the arguments are not pairs or a name is not a string
@exception Lwnrdb.Client.Errors.MappingException when a value does not map to JSON

## Parse(string)
Parses a JSON object.

@param json the JSON text
@returns the document
@exception Lwnrdb.Client.Errors.MappingException when the text is not valid JSON or not an object

## ToJson()
Writes the document as compact JSON.

@returns the JSON text

## With(string, object)
Returns a copy with a field set. Values are encoded as in `Of`.

@param field the field name
@param value the value; null stores a JSON null
@returns the new document

## Without(string)
Returns a copy without a field.

@param field the field name
@returns the new document

## Empty
A document with no fields.

## FieldNames
The names of the top-level fields, in document order.

## Id
The `_id` field when it is a string, otherwise null.
