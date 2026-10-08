# Test Data

Manual test playbook — send each JSON command to the server over TCP, one line at a time.

Anything but `AUTHENTICATE`, `LIST_DATABASES` and `CLOSE_CONNECTION` before authenticating → `401-1`

```json
{"type": "LIST_COLLECTIONS", "databaseName": "test"}
```

A wrong password, or a user that does not exist → `401-3`

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "not-the-password"}
```

Authenticate (must be done before any protected operation)

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "administrator"}
```

A connection past `maxConnections` (100 by default) gets a single `CLOSE_CONNECTION` frame carrying `503-1`
and is closed before it can send anything. There is no command for it: set `maxConnections=2` in
`lwnrdb.cfg` and open a third connection.

Create database

```json
{"type": "CREATE_DATABASE", "databaseName": "test"}
```

Creating it again → `409-2`

```json
{"type": "CREATE_DATABASE", "databaseName": "test"}
```

List all databases

```json
{"type": "LIST_DATABASES"}
```

Create a collection in that database

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "testCollection"}
```

A collection in a database that was never created → `404-4`, with nothing written to disk

```json
{"type": "CREATE_COLLECTION", "databaseName": "neverCreated", "collectionName": "orphan"}
```

A name that differs from an existing collection only by case → `409-11`, because on a case-insensitive
disk both would share one folder

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "TESTCOLLECTION"}
```

The reserved database names `admin`, `admin_pages` and `cluster` cannot be created, in any case → `400-1`

```json
{"type": "CREATE_DATABASE", "databaseName": "cluster"}
```

List all collections of a database

```json
{"type": "LIST_COLLECTIONS", "databaseName": "test"}
```

Create a test object in collection

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "name": "test" }}
```

Create a test object in collection with specified id

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "name": "test with id", "_id": "1234" }}
```

Create a test object in collection with other field

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "otherField": "other field 1", "_id": "findme" }}
```

Create a test object in collection with other field

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "thirdField": "third field" }}
```

Update an object

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "name": "test with id updated 1", "_id": "1234" }}
```

Insert after update

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "test": "this was inserted after an update", "_id": "afterUpdate" }}
```

Update that last one

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "test": "this was inserted after an update", "fieldAdded": "an added field", "_id": "afterUpdate" }}
```

Add a new one with same field

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"fieldAdded": "with other value" }}
```

Add a new one with an array with one element

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"array": ["one"] }}
```

Add a new one with an array with multiple elements

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"array": ["thing", "other thing", "third thing"] }}
```

Bulk save — insert several documents in one request (the response lists the inserted and updated ids)

```json
{"type": "BULK_SAVE", "databaseName": "test", "collectionName": "testCollection", "objects": [{"_id": "bulk-a", "bulk": 1}, {"_id": "bulk-b", "bulk": 2}, {"bulk": 3}]}
```

Bulk save again — an existing `_id` is an update, a new one an insert

```json
{"type": "BULK_SAVE", "databaseName": "test", "collectionName": "testCollection", "objects": [{"_id": "bulk-a", "bulk": 11}, {"_id": "bulk-c", "bulk": 4}]}
```

A repeated `_id` within one request is rejected → `400-3`

```json
{"type": "BULK_SAVE", "databaseName": "test", "collectionName": "testCollection", "objects": [{"_id": "dup", "n": 1}, {"_id": "dup", "n": 2}]}
```

A document larger than `maxEntrySize` (`1Mb` by default) → `400-2`, and nothing is written. To try it by hand,
set `maxEntrySize=1Kb` in `lwnrdb.cfg` and send a document whose `blob` alone is over a kilobyte:

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "too-big", "blob": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"}}
```

Delete the one with id 1234

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "1234"}
```

Find by id

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "findme"}
```

Find by id deleted document

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "1234"}
```

`dirtyRead: true` skips the collection read lock, so the read never waits behind a writer — at the price of
possibly seeing a write that is still landing. `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS` and `LIST_USERS`
accept it.

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "findme", "dirtyRead": true}
```

A read of a collection that is not registered → `404-11`. That includes a differently-cased spelling of a
real one; `AGGREGATE` and `LISTEN` answer the same way.

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "noSuchCollection", "_id": "findme"}
```

Aggregation with filter step matching string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "fieldAdded", "value": "an added field"}}]}
```

Aggregation with filter step not matching string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "fieldAdded", "value": "an added field"}}]}
```

A filter nothing matches → `404-3`: an empty result is answered as `NOT_FOUND`, not as an empty list

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "fieldAdded", "value": "no such value"}}]}
```

`analyze: true` adds an `analyzeResult`: whether an index was used and which ones, how many documents were
scanned, the locks taken, script invocations and their time, how long it took, and index suggestions. With
it, an empty result is `OK` rather than `404-3`, since the analysis is still worth returning.

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "analyze": true, "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "fieldAdded", "value": "no such value"}}]}
```

Insert numeric one to be searched for

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 5 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 10 }}
```

Aggregation with filter matching a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 5}}]}
```

Aggregation with filter not a matching a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "aNumber", "value": 5}}]}
```

Aggregation with filter smaller than a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN", "field": "aNumber", "value": 7}}]}
```

Aggregation with filter smaller than equals a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 5}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 4}}]}
```

Aggregation with filter greater than a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 7}}]}
```

Aggregation with filter greater than equals a number

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aNumber", "value": 11}}]}
```

Insert boolean one to be searched for

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aBoolean": true }}
```

Aggregation with filter equals a boolean

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "aBoolean", "value": true}}]}
```

Aggregation with filter not equals a boolean

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "aBoolean", "value": false}}]}
```

Insert string one to be searched for

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "hola" }}
```

Aggregation with filter in

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "IN", "field": "aString", "value": ["hola", "frescas"]}}]}
```

Aggregation with filter nin

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_IN", "field": "aString", "value": ["asd", "frescas"]}}]}
```

`null` in an `IN` list matches a field that is explicitly `null`, as `EQUALS null` does. A document missing
the field matches neither `IN` nor `NOT_IN`.

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "nullString", "aString": null}}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "IN", "field": "aString", "value": [null, "hola"]}}]}
```

Aggregation with filter contains

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "CONTAINS", "field": "aString", "value": "la"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "CONTAINS", "field": "aString", "value": "holaa"}}]}
```

AND

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "hola", "aNumber":10 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"AND","operators": [{"fieldOperatorType": "CONTAINS", "field": "aString", "value": "la"},{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 7}]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"AND","operators": [{"fieldOperatorType": "EQUALS", "field": "aString", "value": "hola"},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 10}]}}]}
```

OR

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"OR","operators": [{"fieldOperatorType": "EQUALS", "field": "aBoolean", "value": true},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 5}]}}]}
```

XOR

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"XOR","operators": [{"fieldOperatorType": "EQUALS", "field": "aBoolean", "value": true},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 5}]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"XOR","operators": [{"fieldOperatorType": "EQUALS", "field": "aString", "value": "hola"},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 10}]}}]}
```

NOR

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"NOR","operators": [{"fieldOperatorType": "EQUALS", "field": "aBoolean", "value": true},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 5}]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"NOR","operators": [{"fieldOperatorType": "EQUALS", "field": "aString", "value": "hola"},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 10}]}}]}
```

NAND

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"NAND","operators": [{"fieldOperatorType": "EQUALS", "field": "aString", "value": "hola"},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 10}]}}]}
```

Group by

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "otra cosa", "aNumber":12 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "GROUP_BY", "fieldName": "aNumber"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aNumber", "value": 10}},{"type": "GROUP_BY", "fieldName": "aNumber"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "GROUP_BY", "fieldName": "aString"}]}
```

Skip

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SKIP", "skip": 7}]}
```

Limit

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "LIMIT", "limit": 2}]}
```

Count

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "COUNT"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aNumber", "value": 10}},{"type": "COUNT"}]}
```

Distinct

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "DISTINCT", "fieldName": "aNumber"}]}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "otra cosa", "aNumber":12 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"AND","operators": [{"fieldOperatorType": "EQUALS", "field": "aString", "value": "otra cosa"},{"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 12}]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "DISTINCT", "fieldName": null}]}
```

Sort

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aNumber", "ascending":true}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aNumber", "ascending":false}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aString", "ascending":true}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aString", "ascending":false}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aDatetime", "ascending":false}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aDatetime", "ascending":true}]}
```

Add nested objects

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "anObject": { "nested": 7 } }}
```

nested object

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "anObject.nested", "ascending":false}]}
```

Nested objects

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "anObject.nested", "value": 7}}]}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "anObject": { "that": {"is":{"deeply":{"nested":{"value":2}}}}}}}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "anObject.that.is.deeply.nested.value", "value": 2}}]}
```

Join

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "joinMe"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": { "joinField": 5, "anotherField": "hi", "anotherOne": 123 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": { "joinField": 10, "anotherField": "frescas", "anotherOne": 4234 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": { "joinField": 12, "anotherField": "birras", "anotherOne": 5435 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": { "joinField": 5, "anotherField": "hola", "anotherOne": 645654 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": { "joinField": 10, "anotherField": "asdasd", "anotherOne": 2 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "JOIN", "joinCollection": "joinMe", "localField": "aNumber", "remoteField": "joinField", "asField": "joined"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "aNumber", "value": 5}},{"type": "JOIN", "joinCollection": "joinMe", "localField": "aNumber", "remoteField": "joinField", "asField": "joined"}]}
```

Map

Add field "average" → type "AVG"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "average", "condition": null, "operator": {"type":"AVG", "operands": ["aNumber", 20]}}]}]}
```

Add field "sum" → type "SUM"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "sum", "condition": null, "operator": {"type":"SUM", "operands": ["aNumber", 100]}}]}]}
```

Add field "subs" → type "SUBS"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "subs", "condition": null, "operator": {"type":"SUBS", "operands": ["aNumber", 100]}}]}]}
```

Add field "max" → type "MAX"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "max", "condition": null, "operator": {"type":"MAX", "operands": ["aNumber", 7]}}]}]}
```

Add field "min" → type "MIN"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "min", "condition": null, "operator": {"type":"MIN", "operands": ["aNumber", 7]}}]}]}
```

Add field "multiply" → type "MULTIPLY"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "multiply", "condition": null, "operator": {"type":"MULTIPLY", "operands": ["aNumber", 2]}}]}]}
```

Add field "divided" → type "DIVIDE"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "divided", "condition": null, "operator": {"type":"DIVIDE", "operands": ["aNumber", 2]}}]}]}
```

Add field "powered" → type "POW"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "powered", "condition": null, "operator": {"type":"POW", "operands": ["aNumber", 2]}}]}]}
```

Add field "rooted" → type "ROOT"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "rooted", "condition": null, "operator": {"type":"ROOT", "operands": ["aNumber", 2]}}]}]}
```

Add field "absolute" → type "ABS"

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": -3 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "absolute", "condition": null, "operator": {"type":"ABS", "operand": "aNumber"}}]}]}
```

Add field "size" → type "SIZE"

array

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "anArray": ["this", "is", "an", "array"] }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "size", "condition": null, "operator": {"type":"SIZE", "operand": "anArray"}}]}]}
```

string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "size", "condition": null, "operator": {"type":"SIZE", "operand": "aString"}}]}]}
```

Add field "concatenated" → type "CONCAT"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "concatenated", "condition": null, "operator": {"type":"CONCAT", "operands": ["aString", 73, "-birras", false, "aNumber"]}}]}]}
```

Add field "cast" → type "CAST"

number to string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "casted", "condition": null, "operator": {"type":"CAST", "fieldName": "aNumber", "toType": "STRING"}}]}]}
```

boolean to string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "casted", "condition": null, "operator": {"type":"CAST", "fieldName": "aBoolean", "toType": "STRING"}}]}]}
```

string to number

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "13.48" }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "casted", "condition": null, "operator": {"type":"CAST", "fieldName": "aString", "toType": "NUMBER"}}]}]}
```

string to boolean

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "true" }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "casted", "condition": null, "operator": {"type":"CAST", "fieldName": "aString", "toType": "BOOLEAN"}}]}]}
```

number to boolean

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 0 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "casted", "condition": null, "operator": {"type":"CAST", "fieldName": "aNumber", "toType": "BOOLEAN"}}]}]}
```

Add field "total" with a script → type "SCRIPT" (needs scriptsEnabled and the caller's scriptPermissions)

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "total", "condition": null, "operator": {"type":"SCRIPT", "script": "export default (doc) => doc.aNumber * 2;"}}]}]}
```

Filter with a script predicate

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"script": "export default (doc) => doc.aNumber > 5;"}}]}
```

Fold the stream into a single document → step "REDUCE" (optional "initialValue" and "resultField", the latter defaulting to "value")

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "REDUCE", "resultField": "total", "initialValue": 0, "script": "export default (acc, doc) => acc + doc.aNumber;"}]}
```

Add field "addedAfterMap" with condition "AND" → type "MULTIPLY"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "addedAfterMap", "condition": {"conjunctionType":"AND","operators": [{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 10},{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 7}]}, "operator": {"type":"MULTIPLY", "operands": ["aNumber", 3]}}]}]}
```

Add field "addedAfterMap" with condition "OR" → type "MULTIPLY"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "addedAfterMap", "condition": {"conjunctionType":"OR","operators": [{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 10},{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 7}]}, "operator": {"type":"MULTIPLY", "operands": ["aNumber", 3]}}]}]}
```

Remove field "aString"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "aString", "condition": null}]}]}
```

Create database and collection to be dropped

```json
{"type": "CREATE_DATABASE", "databaseName": "dropMe"}
```

```json
{"type": "CREATE_COLLECTION", "databaseName": "dropMe", "collectionName": "dropMeCollection"}
```

add one document to collection

```json
{"type": "SAVE", "databaseName": "dropMe", "collectionName": "dropMeCollection", "object": { "name": "test" }}
```

Drop collection

```json
{"type": "DROP_COLLECTION", "databaseName": "dropMe", "collectionName": "dropMeCollection"}
```

Drop database

```json
{"type": "DROP_DATABASE", "databaseName": "dropMe"}
```

Indexes

Create index

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "this is a string" }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 15 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": true }}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aString"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 14 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 10.10 }}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aNumber"}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aBoolean"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": 12 }}
```

An index on a collection that does not exist → `404-11`, rather than an `OK` for an index that would never exist

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "noSuchCollection", "fieldName": "aNumber"}
```

Drop index

```json
{"type": "DROP_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aString"}
```

```json
{"type": "DROP_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aNumber"}
```

Reindex — rebuild field indexes from the documents, the repair for an index left stale by a failed
background update. Naming the fields rebuilds only those; omitting `fieldNames` rebuilds every
registered index on the collection.

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aNumber"}
```

```json
{"type": "REINDEX", "databaseName": "test", "collectionName": "testCollection", "fieldNames": ["aNumber"]}
```

```json
{"type": "REINDEX", "databaseName": "test", "collectionName": "testCollection"}
```

A field with no registered index → `404-6`

```json
{"type": "REINDEX", "databaseName": "test", "collectionName": "testCollection", "fieldNames": ["neverIndexed"]}
```

Update index with new entry

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "new thing" }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 10 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 10, "_id": "thisis-10" }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "ten", "_id": "thisis-10" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-10" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 7 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 7, "_id": "thisis-7" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-7" }
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "188c2ba1-a28c-40fa-a7e2-70e3b3cd5652" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 15 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 15, "_id": "thisis-15" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-15" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 11 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 11, "_id": "thisis-11" }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": "this is now a string 11", "_id": "thisis-11" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-11" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 13 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 13, "_id": "thisis-13" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-13" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 14}}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": 14, "_id": "thisis-14" }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "thisis-14" }
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aString": true }}
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "55882e3b-a401-4a16-bea1-6477fdb36762" }
```

```json
{"type": "DELETE", "databaseName": "test", "collectionName": "testCollection", "_id": "294dba55-bdbf-44c5-9f51-e44a2fa8aa5a" }
```

Search with index

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "IN", "field": "aString", "value": ["hola", "frescas"]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_IN", "field": "aString", "value": ["asd", "frescas"]}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 0}}, {"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN", "field": "aNumber", "value": 10}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"conjunctionType":"AND","operators": [{"fieldOperatorType": "IN", "field": "aString", "value": ["AAA", "BBB"]},{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 500}]}}]}
```

Close connection

```json
{"type": "CLOSE_CONNECTION"}
```

Once the server starts shutting down, any request on a connection that is still open → `503-13`, except
`ROLLBACK_TRANSACTION` and `CLOSE_CONNECTION`, which still answer so an open transaction can be released.
Send a request right after stopping the server (Ctrl+C, or `SIGTERM`) to see it.

Users and permissions

Re-authenticate after reconnecting

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "administrator"}
```

Create a non-admin user with read-write access to one database, allowed to run scripts on it

```json
{"type": "CREATE_USER", "username": "Alice", "password": "secret1234", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {"test": true}}
```

Create a read-only user scoped to a single collection

```json
{"type": "CREATE_USER", "username": "readonly", "password": "readonly1234", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {"test|testCollection": "READ"}, "scriptPermissions": {}}
```

Create a user that can create and drop databases

```json
{"type": "CREATE_USER", "username": "dbadmin", "password": "dbadmin1234", "admin": false, "globalPermissions": ["CREATE_DATABASE", "DROP_DATABASE"], "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {}}
```

A user that already exists → `409-1`

```json
{"type": "CREATE_USER", "username": "readonly", "password": "readonly1234", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {"test|testCollection": "READ"}, "scriptPermissions": {}}
```

A `LIST_USERS` filter that matches nobody → `404-5`

```json
{"type": "LIST_USERS", "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "_id", "value": "nobody"}}]}
```

Grant Alice admin rights and update her permissions (an admin needs no script grant)

```json
{"type": "CHANGE_PERMISSIONS", "username": "Alice", "admin": true, "globalPermissions": ["CREATE_DATABASE", "DROP_DATABASE"], "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {}}
```

Downgrade Alice back to a regular user, keeping her script grant on `test`

```json
{"type": "CHANGE_PERMISSIONS", "username": "Alice", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {"test": true}}
```

Authenticate as Alice

```json
{"type": "AUTHENTICATE", "username": "Alice", "password": "secret1234"}
```

Authenticate back as admin

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "administrator"}
```

Change a user's password as admin — no `currentPassword` needed

```json
{"type": "SET_PASSWORD", "username": "Alice", "newPassword": "new_secret_1234"}
```

A user that does not exist → `404-1` (`DELETE_USER` and `CHANGE_PERMISSIONS` answer the same)

```json
{"type": "SET_PASSWORD", "username": "nobody", "newPassword": "whatever1234"}
```

Authenticate with the new password

```json
{"type": "AUTHENTICATE", "username": "Alice", "password": "new_secret_1234"}
```

A user changing their own password must prove the current one

```json
{"type": "SET_PASSWORD", "username": "Alice", "currentPassword": "new_secret_1234", "newPassword": "secret1234"}
```

The wrong current password is refused → `400-6`

```json
{"type": "SET_PASSWORD", "username": "Alice", "currentPassword": "not_the_password", "newPassword": "whatever1234"}
```

Back to admin

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "administrator"}
```

Database owners — an owner has full access to the database and may drop it. Admin only; the list
replaces the current owners outright.

```json
{"type": "SET_DATABASE_OWNERS", "databaseName": "test", "owners": ["Alice"]}
```

```json
{"type": "LIST_DATABASES"}
```

An unknown user cannot be made an owner → `400-1`

```json
{"type": "SET_DATABASE_OWNERS", "databaseName": "test", "owners": ["nobody"]}
```

Clear the owners again

```json
{"type": "SET_DATABASE_OWNERS", "databaseName": "test", "owners": []}
```

The last admin can be neither demoted → `400-5`

```json
{"type": "CHANGE_PERMISSIONS", "username": "admin", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {}}
```

nor deleted → `400-4`

```json
{"type": "DELETE_USER", "username": "admin"}
```

Deleting a user signs out every connection still authenticated as them: if Alice is connected on another
connection, her next request there answers `401-1` until she authenticates again. A user created later under
the same name does not inherit those connections.

Delete Alice

```json
{"type": "DELETE_USER", "username": "Alice"}
```

Delete readonly

```json
{"type": "DELETE_USER", "username": "readonly"}
```

Delete dbadmin

```json
{"type": "DELETE_USER", "username": "dbadmin"}
```

Scripts (`RUN_SCRIPT`)

Scripting is on by default; with `scriptsEnabled=false` in `lwnrdb.cfg` every `RUN_SCRIPT` below is
refused with `403-2`. A script is scoped to the request's `databaseName` and may use any collection in
it; `db.name` is that database, so a script never hardcodes it.

Simplest script — the top-level `return` value comes back in `result`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 1 + 1;"}
```

Console output is returned in `logs` (newest `scriptMaxLogLines` lines)

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "console.log('hello from the script');\nconsole.log('and again');\nreturn 'done';"}
```

Arguments — the optional `args` object is read through `import args from "args"`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import args from \"args\";\nreturn args.name + ' is ' + args.age;", "args": {"name": "Alice", "age": 30}}
```

Read a document by id

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\nreturn db.findById(db.name, 'testCollection', 'findme');"}
```

Run an aggregation pipeline from a script

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\nconst pipeline = [{ type: 'FILTER', operator: { fieldOperatorType: 'GREATER_THAN_EQUALS', field: 'aNumber', value: 10 } }];\nconst rows = db.aggregate(db.name, 'testCollection', pipeline);\nconsole.log(`matched ${rows.length} documents`);\nreturn rows.length;"}
```

List the collections of the scoped database

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\nreturn db.listCollections(db.name);"}
```

Save a document and read it back

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ndb.save(db.name, 'testCollection', { _id: 'scripted-1', scripted: true, aNumber: 42 });\nreturn db.findById(db.name, 'testCollection', 'scripted-1');"}
```

Bulk save — returns the inserted and updated ids

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\nreturn db.bulkSave(db.name, 'testCollection', [{ _id: 'bulk-1', n: 1 }, { _id: 'bulk-2', n: 2 }]);"}
```

Delete a document (deleting one that is not there is a no-op)

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ndb.delete(db.name, 'testCollection', 'scripted-1');\ndb.delete(db.name, 'testCollection', 'never-existed');\nreturn db.findById(db.name, 'testCollection', 'scripted-1') === null;"}
```

Transaction spanning two collections — both writes commit together

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ndb.transaction(() => {\n    db.save(db.name, 'testCollection', { _id: 'tx-1', from: 'transaction' });\n    db.save(db.name, 'joinMe', { _id: 'tx-2', joinField: 99 });\n});\nreturn 'committed';"}
```

A throw inside the callback rolls the whole transaction back

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ntry {\n    db.transaction(() => {\n        db.save(db.name, 'testCollection', { _id: 'tx-rolled-back', from: 'transaction' });\n        throw new Error('abort');\n    });\n} catch (e) {\n    return { message: e.message, written: db.findById(db.name, 'testCollection', 'tx-rolled-back') !== null };\n}"}
```

Named exports are the result when there is no top-level `return`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "export const total = 3;\nexport const label = 'exported';"}
```

A failed database operation throws into the script and can be caught

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ntry {\n    db.save(db.name, 'neverCreatedCollection', { _id: 'x' });\n    return 'wrote';\n} catch (e) {\n    return { caught: e instanceof Error, message: e.message };\n}"}
```

A script cannot leave its database — not even `admin` (caught inside the script)

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import db from \"db\";\ntry {\n    return db.findById('admin', 'users', 'admin');\n} catch (e) {\n    return e.message;\n}"}
```

An uncaught throw fails the run with `400-9` (`logs` are still returned)

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "console.log('before the throw');\nthrow new TypeError('boom');"}
```

A syntax error also fails with `400-9`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "function ("}
```

A tight loop exhausts `scriptInstructionBudget` → `400-11`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "while (true) {}"}
```

Waiting past `scriptTimeoutMs` → `408-1`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return (async () => { await new Promise(r => setTimeout(r, 60000)); return 'never'; })();"}
```

A returned promise that never settles → `400-20`, rather than a silent `null`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return new Promise(() => {});"}
```

Allocating past `scriptMaxMemoryBytes` (`64Mb` by default) → `400-12`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "const parts = [];\nwhile (true) { parts.push('x'.repeat(1000000)); }"}
```

A result larger than `scriptMaxResultBytes` (`16Mb` by default) → `400-15`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 'x'.repeat(17000000);"}
```

A source larger than `scriptMaxSourceBytes` (`256Kb` by default) → `400-10`, before anything is parsed. The
same cap refuses a `SAVE_PROCEDURE` and a pipeline `SCRIPT` operator. To try it by hand, set
`scriptMaxSourceBytes=1Kb` in `lwnrdb.cfg` and send any script over a kilobyte.

Unknown database → `404-4`

```json
{"type": "RUN_SCRIPT", "databaseName": "nosuchdb", "script": "return 1;"}
```

The reserved `admin` database cannot be scripted → `400-1`

```json
{"type": "RUN_SCRIPT", "databaseName": "admin", "script": "return 1;"}
```

A missing or blank `script` → `400-1`

```json
{"type": "RUN_SCRIPT", "databaseName": "test"}
```

Not allowed while a transaction is open on the connection → `409-6`

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 1;"}
```

```json
{"type": "ROLLBACK_TRANSACTION"}
```

Per-database script permissions

Admins may script any database and database owners the databases they own. Anybody else needs a grant
for that specific database in `scriptPermissions`; an absent entry or an explicit `false` is a denial.

A second database, to show that a grant does not carry over

```json
{"type": "CREATE_DATABASE", "databaseName": "test2"}
```

A user allowed to script `test` only (read access to the data, script grant on `test`)

```json
{"type": "CREATE_USER", "username": "scripter", "password": "scripter1234", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE", "test2": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {"test": true}}
```

A user with data access but no script grant at all

```json
{"type": "CREATE_USER", "username": "noscripts", "password": "noscripts1234", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {}}
```

Authenticate as scripter

```json
{"type": "AUTHENTICATE", "username": "scripter", "password": "scripter1234"}
```

Allowed on the granted database

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 'scripter ran this';"}
```

Refused on `test2` despite having READ_WRITE there → `403-1`

```json
{"type": "RUN_SCRIPT", "databaseName": "test2", "script": "return 1;"}
```

Authenticate as noscripts

```json
{"type": "AUTHENTICATE", "username": "noscripts", "password": "noscripts1234"}
```

Refused everywhere → `403-1`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 1;"}
```

Back to admin

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "administrator"}
```

`CHANGE_PERMISSIONS` replaces **all** permissions, so omitting `scriptPermissions` (or sending `{}`)
revokes every script grant the user had.

Revoke scripter's grant

```json
{"type": "CHANGE_PERMISSIONS", "username": "scripter", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {}}
```

Grant it again, on both databases this time

```json
{"type": "CHANGE_PERMISSIONS", "username": "scripter", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE", "test2": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {"test": true, "test2": true}}
```

An explicit `false` is a denial, not a grant

```json
{"type": "CHANGE_PERMISSIONS", "username": "scripter", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}, "scriptPermissions": {"test": false}}
```

A grant naming the reserved `admin` database is rejected → `400-1`

```json
{"type": "CHANGE_PERMISSIONS", "username": "scripter", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {"admin": true}}
```

A non-boolean grant value is rejected → `400-1`

```json
{"type": "CHANGE_PERMISSIONS", "username": "scripter", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {}, "scriptPermissions": {"test": "READ"}}
```

Script grants show up in `LIST_USERS`

```json
{"type": "LIST_USERS", "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "_id", "value": "scripter"}}]}
```

Clean up

```json
{"type": "DELETE_USER", "username": "scripter"}
```

Clean up

```json
{"type": "DELETE_USER", "username": "noscripts"}
```

Clean up

```json
{"type": "DROP_DATABASE", "databaseName": "test2"}
```

Running scripts: visibility and cancellation (admin only)

`LIST_SCRIPTS` reports every script executing right now — ad-hoc `RUN_SCRIPT`s, `CALL_PROCEDURE`s,
trigger runs and scheduled runs alike — and `CANCEL_SCRIPT` stops one by its `runId`. Both fan out to
every live member, so a run is visible and cancellable from any node, not only the one executing it.

Unlike everything else in this playbook these need **two connections**: the run has to still be in
flight when you look for it. Send the slow script on connection A, then the listing and the cancel on
connection B.

Nothing running yet — an empty `scripts` list

```json
{"type": "LIST_SCRIPTS"}
```

On connection A, a script that runs for a minute (leave it waiting)

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "export default new Promise(r => setTimeout(() => r('done'), 60000));"}
```

On connection B, the run now appears — `runId`, the `node` executing it, its `kind`, the `database`, the
`username` whose authority it runs with, and `ageMs`. On a standalone server `node` reads `local`; in a
cluster it is the executing node's `host:clusterPort`.

```json
{"type": "LIST_SCRIPTS"}
```

While it runs, admission is also visible. With `maxConcurrentScripts=1` in `lwnrdb.cfg`, a second script on
connection B waits `scriptQueueWaitMs` (250 ms by default) for a permit and is then refused → `503-6`. The
message names the limit that refused it; `maxConcurrentScriptsPerUser` and `maxConcurrentScriptsPerDatabase`
refuse the same way.

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "return 1;"}
```

Stop it, using the `runId` from that listing. Connection A's `RUN_SCRIPT` then answers `408-2`, and the
`runId` on its response is the same one.

```json
{"type": "CANCEL_SCRIPT", "runId": "00000000-0000-0000-0000-000000000000"}
```

Every `RUN_SCRIPT` and `CALL_PROCEDURE` response carries its own `runId`, so a caller can name its run
without listing first.

A `runId` no live node is running is not an error — the answer is `OK` with `cancelled: false`

```json
{"type": "CANCEL_SCRIPT", "runId": "00000000-0000-0000-0000-000000000000"}
```

A missing `runId` → `400-1`

```json
{"type": "CANCEL_SCRIPT"}
```

A `runId` that is not a UUID → `400-1`

```json
{"type": "CANCEL_SCRIPT", "runId": "not-a-uuid"}
```

Cancellation is not catchable and skips `finally`. Run this on connection A and cancel it from B: it
answers `408-2`, not `"caught"`, and the `finally` block never runs.

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "try {\n    while (true) {}\n} catch (e) {\n    return 'caught';\n} finally {\n    console.log('this never runs');\n}"}
```

A `CALL_PROCEDURE`, a trigger run and a scheduled run are listed the same way, with `kind` set to
`CALL_PROCEDURE`, `TRIGGER` or `SCHEDULE` and `name` naming the procedure, trigger or schedule (the
sections below install those; `name` is `null` for an ad-hoc `RUN_SCRIPT`). Cancelling a **trigger** run
drops it: its pending run record is consumed, so startup recovery will not replay it — the one place the
exactly-once trigger guarantee is deliberately waived.

Both operations are admin-only: any non-admin — even a database owner holding a `MANAGE` script grant —
gets `403-1`. The counters `running` and `cancelled` in `GET_DATABASE_STATS` report the same numbers per
node.

Recorded trigger runs: listing and resolving (admin only)

A failed after-trigger is retried up to `triggerMaxAttempts` with a doubling backoff, and then
dead-lettered: its record is kept with the last error instead of being discarded. `LIST_TRIGGER_RUNS`
finds those, `RESOLVE_TRIGGER_RUN` acts on one. Both fan out to every live member, because
`admin/trigger_runs` is not replicated and a run's record lives on exactly one node.

A run whose own transaction committed only partly (`500-33`, or `409-10` from a single remote owner) is
dead-lettered at once rather than retried. Recovery finishes that commit, so a retry would apply the
trigger body twice. This holds even when the script then throws from a timer or a promise. The record's
error starts with `CommitFenced`.

Everything still recorded — pending runs and dead letters alike

```json
{"type": "LIST_TRIGGER_RUNS"}
```

Only the ones an operator has to act on

```json
{"type": "LIST_TRIGGER_RUNS", "status": "DEAD"}
```

An unrecognised status → `400-1`

```json
{"type": "LIST_TRIGGER_RUNS", "status": "sideways"}
```

Replay one after fixing its procedure. The attempt count starts over, so the corrected run gets a full
budget rather than dead-lettering again on its first failure.

```json
{"type": "RESOLVE_TRIGGER_RUN", "runId": "00000000-0000-0000-0000-000000000000", "decision": "replay"}
```

Or give up on it for good

```json
{"type": "RESOLVE_TRIGGER_RUN", "runId": "00000000-0000-0000-0000-000000000000", "decision": "discard"}
```

A `runId` no live node holds is not an error — `OK` with `resolved: false`, the answer `CANCEL_SCRIPT`
gives for a run that has already finished. A missing `runId` or an unknown `decision` → `400-1`.

```json
{"type": "RESOLVE_TRIGGER_RUN", "runId": "abc", "decision": "maybe"}
```

Run history

Unless `scriptRunHistoryEnabled=false`, a finished run is recorded in the reserved `script_runs`
collection of the database it ran against. It is an ordinary collection to read — and refused to write.

What ran, newest first

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "script_runs", "aggregationSteps": [{"type": "SORT", "fieldName": "startedAt", "ascending": false}]}
```

Only the failures

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "script_runs", "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "outcome", "value": "error"}}]}
```

Writing one by hand is refused — the collection is the server's → `400-1`

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "script_runs", "object": {"_id": "forged", "outcome": "ok"}}
```

Admin-only: get memory & schema stats (heap usage, cache usage vs cap, OS free RAM, totals across databases/collections, plus per-collection page/index/entry breakdown)

```json
{"type": "GET_DATABASE_STATS"}
```

Custom types

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "_id": "aTimeTest", "aTime": "#time(12:00:00)" }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "aTime", "value": "#time(12:00:00)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "aTime", "value": "#time(12:00:01)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN", "field": "aTime", "value": "#time(11:59:59)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aTime", "value": "#time(12:00:00)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN", "field": "aTime", "value": "#time(12:00:01)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aTime", "value": "#time(12:00:00)"}}]}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aTime"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "_id": "aTimeTest", "aTime": 12 }}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "_id": "aDatetimeTest", "aDatetime": "#datetime(2024-07-12T12:00:00)" }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "aDatetime", "value": "#datetime(2024-07-12T12:00:00)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "aDatetime", "value": "#datetime(2024-07-12T12:00:01)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN", "field": "aDatetime", "value": "#datetime(2024-07-12T11:59:59)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "GREATER_THAN_EQUALS", "field": "aDatetime", "value": "#datetime(2024-07-12T12:00:00)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN", "field": "aDatetime", "value": "#datetime(2024-07-12T12:00:01)"}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aDatetime", "value": "#datetime(2024-07-12T12:00:00)"}}]}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aDatetime"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "_id": "aDatetimeTest", "aDatetime": "#time(12:00:00)" }}
```

Geo points are `#geo(latitude,longitude)`. `distance` compares the distance in metres to a target point;
`within` keeps points inside a polygon given as its corners in order.

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "geo-near", "location": "#geo(40.0,-74.0)"}}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "geo-far", "location": "#geo(41.0,-74.0)"}}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"customOperatorName": "distance", "field": "location", "value": "#geo(40.001,-74.0)", "comparator": "SMALLER_THAN", "distance": 1000}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"customOperatorName": "within", "field": "location", "polygon": ["#geo(39.9,-74.1)", "#geo(39.9,-73.9)", "#geo(40.1,-73.9)", "#geo(40.1,-74.1)"]}}]}
```

Vectors are `#vector(...)`. `nearest` returns the `k` documents most similar to the query by cosine
similarity. It is an approximate search by default, so it can miss a document a full scan would rank;
`exact: true` scans every document instead.

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "vec-a", "embedding": "#vector(1.0,0.0)"}}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "vec-b", "embedding": "#vector(0.0,1.0)"}}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"customOperatorName": "nearest", "field": "embedding", "value": "#vector(0.9,0.1)", "k": 1}}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"customOperatorName": "nearest", "field": "embedding", "value": "#vector(0.9,0.1)", "k": 1, "exact": true}}]}
```

Index-backed aggregations

The `GROUP_BY`, `JOIN`, `SORT`, and `DISTINCT` steps use a field index when one exists on the step's field and the step is the first step in the pipeline. Create an index on the field, then run the step as the only step to exercise the index path (the same commands return the same results with or without the index — the index just makes them faster).

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aNumber"}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "DISTINCT", "fieldName": "aNumber"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "GROUP_BY", "fieldName": "aNumber"}]}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "SORT", "fieldName": "aNumber", "ascending": true}]}
```

```json
{"type": "CREATE_INDEX", "databaseName": "test", "collectionName": "joinMe", "fieldName": "joinField"}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "JOIN", "joinCollection": "joinMe", "localField": "aNumber", "remoteField": "joinField", "asField": "joined"}]}
```

Schema validation

A collection may carry one draft-2020-12 JSON Schema. It is checked before the write commits, so a
non-compliant document is rejected rather than stored. Admin or database owner only.

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "people"}
```

```json
{"type": "SAVE_SCHEMA", "databaseName": "test", "collectionName": "people", "schema": {"type": "object", "required": ["name", "age"], "properties": {"name": {"type": "string", "minLength": 1}, "age": {"type": "integer", "minimum": 0}}, "additionalProperties": false}}
```

A compliant document is stored

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "people", "object": {"_id": "p1", "name": "Alice", "age": 30}}
```

A non-compliant one is refused → `400-7`

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "people", "object": {"_id": "p2", "name": "", "age": -1}}
```

An unknown property is refused too, since `additionalProperties` is false → `400-7`

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "people", "object": {"_id": "p3", "name": "Bob", "age": 40, "extra": true}}
```

`BULK_SAVE` is validated the same way → `400-7`

```json
{"type": "BULK_SAVE", "databaseName": "test", "collectionName": "people", "objects": [{"name": "Carol", "age": 20}, {"name": "Dave"}]}
```

A schema that is not itself valid is refused → `400-8`

```json
{"type": "SAVE_SCHEMA", "databaseName": "test", "collectionName": "people", "schema": {"type": "not-a-type"}}
```

A schema the server cannot read never lets a write through unvalidated: every `SAVE` and `BULK_SAVE` to the
collection → `503-11` until the schema is saved again or deleted. To see it, stop the server, replace
`<filePath>/test/people/people-schema.json` with text that is not JSON, and start it again.

Remove the schema — idempotent, so sending it twice still returns OK

```json
{"type": "DELETE_SCHEMA", "databaseName": "test", "collectionName": "people"}
```

```json
{"type": "DELETE_SCHEMA", "databaseName": "test", "collectionName": "people"}
```

The write that was refused above now succeeds

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "people", "object": {"_id": "p2", "name": "", "age": -1}}
```

Live queries (`LISTEN`)

The server runs the aggregation once, answers with a `listenId` and a `resultHash`, then pushes an
updated result set to the same connection whenever the collection changes the results. The pushes
arrive unsolicited, so read the socket after each write below rather than expecting one reply per
request.

```json
{"type": "LISTEN", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "watched", "value": true}}]}
```

This write changes the result set, so a push follows

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "watch-1", "watched": true}}
```

So does this one

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "watch-2", "watched": true}}
```

This one does not match the filter, so nothing is pushed

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "watch-3", "watched": false}}
```

An empty pipeline watches the whole collection. The array is required, so omitting it is `400-1`.

```json
{"type": "LISTEN", "databaseName": "test", "collectionName": "joinMe", "aggregationSteps": []}
```

```json
{"type": "LISTEN", "databaseName": "test", "collectionName": "joinMe"}
```

A script anywhere in a `LISTEN` pipeline → `400-19`, since it would run again on every write

```json
{"type": "LISTEN", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator": {"script": "export default (doc) => doc.aNumber > 5;"}}]}
```

Cancel a subscription (use a `listenId` from a response above)

```json
{"type": "STOP_LISTEN", "listenId": "550e8400-e29b-41d4-a716-446655440000"}
```

An unknown id → `404-7`

```json
{"type": "STOP_LISTEN", "listenId": "00000000-0000-0000-0000-000000000000"}
```

A listen the server ends on its own is told so with one final frame, `410-1`, after which nothing more is
pushed for that `listenId`. That happens when a collection it reads (its own or a `JOIN` target) is dropped,
when its database is dropped, or when the listening user loses read access. Listen on a scratch collection,
then drop it from another connection:

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "listenDropped"}
```

```json
{"type": "LISTEN", "databaseName": "test", "collectionName": "listenDropped", "aggregationSteps": []}
```

```json
{"type": "DROP_COLLECTION", "databaseName": "test", "collectionName": "listenDropped"}
```

The listening connection receives:

```json
{"type": "LISTEN", "status": "NOT_FOUND", "errorCode": "410-1", "message": "The listen ended: a collection it reads was dropped", "listenId": "550e8400-e29b-41d4-a716-446655440000"}
```

After that, `STOP_LISTEN` with that id → `404-7`. A `STOP_LISTEN` or a closed connection ends a listen
without the final frame.

Transactions

A transaction is scoped to the connection: every write between `START_TRANSACTION` and
`COMMIT_TRANSACTION` is buffered and applied atomically. Reads inside it see the buffered writes.
Any authenticated user may open one. Starting, committing and rolling back need no grant, but each
buffered write or read is still authorized on its own, so a write the user could not make outside the
transaction is refused inside it too → `403-1`.

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "tx-commit-1", "committed": true}}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "joinMe", "object": {"_id": "tx-commit-2", "joinField": 77}}
```

A read inside the transaction sees its own buffered write

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "tx-commit-1"}
```

Commit — both collections are written together

```json
{"type": "COMMIT_TRANSACTION"}
```

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "joinMe", "_id": "tx-commit-2"}
```

Rollback discards everything buffered

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "tx-discarded", "committed": false}}
```

```json
{"type": "ROLLBACK_TRANSACTION"}
```

Nothing was written → `404-2`

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "tx-discarded"}
```

Starting a second transaction on the same connection → `409-3`

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "ROLLBACK_TRANSACTION"}
```

Committing with none open → `409-4`

```json
{"type": "COMMIT_TRANSACTION"}
```

A buffered write takes its collection's write lock and keeps it until the transaction ends. Another
transaction that cannot get that lock within `transactionLockTimeoutMs` (5 s by default) → `409-5`, and its
transaction is aborted. This needs two connections. On connection A:

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "tx-lock-a", "holder": "A"}}
```

On connection B, the same collection → `409-5` after the timeout

```json
{"type": "START_TRANSACTION"}
```

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "tx-lock-b", "holder": "B"}}
```

B's transaction is now unusable: anything but `COMMIT_TRANSACTION`, `ROLLBACK_TRANSACTION` and
`CLOSE_CONNECTION` → `409-9`, and a `COMMIT_TRANSACTION` answers `409-9` too and ends it

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "findme"}
```

```json
{"type": "COMMIT_TRANSACTION"}
```

Back on connection A

```json
{"type": "ROLLBACK_TRANSACTION"}
```

In a cluster a transaction can touch collections owned by different nodes, and a few more answers appear:

- `409-7` — a participant could not prepare (it lost quorum, lost ownership of a collection it buffered, or
  never answered), so the commit was aborted everywhere.
- `409-10` — part of the commit did not apply, or the owner stopped waiting for it. Recovery re-drives it;
  re-sending `COMMIT_TRANSACTION` reports how it ended.
- `409-12` — a previous transaction on this connection is still being resolved on a participant; retry
  once it finishes.
- `409-13` — a participant no longer holds this transaction's writes (it restarted, or reaped the session
  of an edge it saw as gone). The transaction can only be rolled back and retried.
- `409-14` — a `ROLLBACK_TRANSACTION` sent after a `409-10` commit that in fact committed on its owner.
- `421-3` — a read inside the transaction (a `JOIN` included) touches collections whose buffered writes
  live on different nodes, so no single node can answer it with the transaction's own writes.

In-doubt distributed transactions (admin only, clustering). Lists every prepared 2PC transaction the
cluster still holds — the input to a manual resolution. On a standalone node the list is empty.

```json
{"type": "LIST_TRANSACTIONS"}
```

Force a decision on an in-doubt transaction, using a `dtxId` from the listing above. Only for a
transaction whose coordinator is gone for good — the decision is broadcast to every member.

```json
{"type": "RESOLVE_TRANSACTION", "dtxId": "00000000-0000-0000-0000-000000000000", "decision": "commit"}
```

```json
{"type": "RESOLVE_TRANSACTION", "dtxId": "00000000-0000-0000-0000-000000000000", "decision": "abort"}
```

A decision other than commit/abort → `400-1`

```json
{"type": "RESOLVE_TRANSACTION", "dtxId": "00000000-0000-0000-0000-000000000000", "decision": "maybe"}
```

Stored procedures

A named script, stored with its database and called by name. Installing one needs admin, database
ownership, or `scriptPermissions` of `MANAGE` on that database; calling one needs `RUN`. The source is
parsed at save time, so a broken body is refused here rather than on somebody else's first call.

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "recalcTotals", "script": "import db from \"db\";\nimport args from \"args\";\nconst o = db.findById(db.name, 'testCollection', args.id);\nreturn o === null ? 0 : o.aNumber * 2;", "description": "doubles aNumber"}
```

Call it — the response carries `result`, `logs` and `logsTruncated`, like `RUN_SCRIPT`

```json
{"type": "CALL_PROCEDURE", "databaseName": "test", "procedureName": "recalcTotals", "args": {"id": "findme"}}
```

List them — metadata only

```json
{"type": "LIST_PROCEDURES", "databaseName": "test"}
```

`includeSource` adds the body

```json
{"type": "LIST_PROCEDURES", "databaseName": "test", "includeSource": true}
```

Re-saving replaces the procedure and bumps its `version`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "recalcTotals", "script": "import args from \"args\";\nreturn 'version two saw ' + args.id;"}
```

```json
{"type": "CALL_PROCEDURE", "databaseName": "test", "procedureName": "recalcTotals", "args": {"id": "findme"}}
```

`ifVersion` is optimistic concurrency — a stale value is refused with `409-8`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "recalcTotals", "script": "return 1;", "ifVersion": 99}
```

`ifVersion: 0` requires that the procedure does not exist yet → `409-8`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "recalcTotals", "script": "return 1;", "ifVersion": 0}
```

A body that does not parse is refused at save time → `400-13`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "brokenProc", "script": "return (;"}
```

A disabled procedure is not callable → `404-8`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "disabledProc", "script": "return 1;", "enabled": false}
```

```json
{"type": "CALL_PROCEDURE", "databaseName": "test", "procedureName": "disabledProc", "args": {}}
```

Calling one that was never installed → `404-8`

```json
{"type": "CALL_PROCEDURE", "databaseName": "test", "procedureName": "neverInstalled", "args": {}}
```

Importing a stored procedure as a module — a library is a procedure that `export`s instead of
returning, imported with `procedures/<name>` (only this database's, and only if it is enabled)

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "money", "script": "export function cents(n) { return Math.round(n * 100); }"}
```

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "totalCents", "script": "import { cents } from \"procedures/money\";\nimport args from \"args\";\nreturn cents(args.amount);"}
```

```json
{"type": "CALL_PROCEDURE", "databaseName": "test", "procedureName": "totalCents", "args": {"amount": 12.345}}
```

Installing a procedure whose import does not resolve is refused → `400-18` (so libraries go in first)

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "brokenImport", "script": "import { nope } from \"procedures/absent\";\nexport const x = nope;"}
```

A script may import one too

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import { cents } from \"procedures/money\";\nreturn cents(3.5);"}
```

An unknown module is a catchable error, so the specifier can be probed → `Cannot find module 'procedures/nope'`

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "try { await import(\"procedures/nope\"); return 'resolved'; } catch (e) { return e.message; }"}
```

A procedure written with a top-level `return` is callable but imports as `undefined` — only `export`ed
bindings cross a module boundary

```json
{"type": "RUN_SCRIPT", "databaseName": "test", "script": "import lib from \"procedures/totalCents\";\nreturn typeof lib;"}
```

Delete — idempotent, so sending it twice still returns OK

```json
{"type": "DELETE_PROCEDURE", "databaseName": "test", "name": "disabledProc"}
```

```json
{"type": "DELETE_PROCEDURE", "databaseName": "test", "name": "disabledProc"}
```

Triggers

A trigger runs a stored procedure after a committed write. It fires asynchronously, so it cannot
reject or modify the write — use a collection schema for that — and it runs with the authority of
whoever installed it (`definer`), not the writer's. Set `triggersEnabled=true` in `lwnrdb.cfg` for
triggers to actually fire; the DDL below works either way.

The procedure a trigger will call — its `args` carry the event, the document and who wrote it

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "auditLog"}
```

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "auditWrite", "script": "import db from \"db\";\nimport args from \"args\";\ndb.save(db.name, 'auditLog', { _id: args.id + '-' + args.firedAt, event: args.event, by: args.actingUser, definer: args.definer });\nreturn 'audited';"}
```

Install it on the collection

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "auditWrites", "events": ["CREATED", "UPDATED", "DELETED"], "procedureName": "auditWrite", "mode": "document", "allowCascade": false, "enabled": true}
```

This write fires it — check `auditLog` a moment later

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "triggered-1", "n": 1}}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "auditLog", "aggregationSteps": [{"type": "COUNT"}]}
```

Batch mode — one run for a whole `BULK_SAVE`, with `documents` instead of `id`/`document`

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "auditBatch", "script": "import db from \"db\";\nimport args from \"args\";\ndb.save(db.name, 'auditLog', { _id: 'batch-' + args.firedAt, count: args.documents.length });\nreturn 'audited';"}
```

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "joinMe", "name": "auditBatchWrites", "events": ["CREATED"], "procedureName": "auditBatch", "mode": "batch"}
```

```json
{"type": "BULK_SAVE", "databaseName": "test", "collectionName": "joinMe", "objects": [{"joinField": 1}, {"joinField": 2}]}
```

A **before** trigger runs synchronously, before the write, and may veto it or replace the document

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "normalizeOrder", "script": "export default function (doc, ctx) {\n  if (doc.qty < 0) { throw new Error('qty must not be negative'); }\n  if (ctx.event === 'DELETED') { return; }\n  return { ...doc, total: doc.qty * 10 };\n}"}
```

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "normalizeOrder", "events": ["CREATED", "UPDATED"], "procedureName": "normalizeOrder", "timing": "before"}
```

The stored document carries the computed field — no polling, the hook ran before the commit

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "beforeHooked", "qty": 3}}
```

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "testCollection", "_id": "beforeHooked"}
```

A document the hook throws on is refused → `400-21`, and nothing is written

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": {"_id": "beforeRejected", "qty": -1}}
```

`batch` mode and `allowCascade` are meaningless for a before trigger (it has no `db`) → `400-14`

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "badBefore", "events": ["CREATED"], "procedureName": "normalizeOrder", "timing": "before", "mode": "batch"}
```

Try one against a document without writing anything — `decision` is `accept`, `replace` or `reject`

```json
{"type": "TEST_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "normalizeOrder", "event": "CREATED", "document": {"_id": "dryRun", "qty": 4}}
```

```json
{"type": "TEST_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "normalizeOrder", "event": "CREATED", "document": {"_id": "dryRun", "qty": -1}}
```

A name with no trigger on that collection → `404-9`

```json
{"type": "TEST_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "noSuchTrigger", "event": "CREATED", "document": {"_id": "dryRun", "qty": 4}}
```

Only a before trigger can be tested, because only it is directly callable → `400-14`

```json
{"type": "TEST_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "auditWrites", "event": "CREATED", "document": {"_id": "dryRun"}}
```

List them — omit `collectionName` to list every trigger in the database

```json
{"type": "LIST_TRIGGERS", "databaseName": "test", "collectionName": "testCollection"}
```

```json
{"type": "LIST_TRIGGERS", "databaseName": "test"}
```

A trigger pointing at a procedure that does not exist → `404-8`

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "danglingTrigger", "events": ["CREATED"], "procedureName": "neverInstalled"}
```

No events, or an unknown one → `400-14`

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "noEvents", "events": [], "procedureName": "auditWrite"}
```

```json
{"type": "SAVE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "badEvent", "events": ["EXPLODED"], "procedureName": "auditWrite"}
```

Deleting a procedure a trigger still references is refused → `400-14`

```json
{"type": "DELETE_PROCEDURE", "databaseName": "test", "name": "auditWrite"}
```

Delete — idempotent, so sending it twice still returns OK

```json
{"type": "DELETE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "auditWrites"}
```

```json
{"type": "DELETE_TRIGGER", "databaseName": "test", "collectionName": "testCollection", "name": "auditWrites"}
```

```json
{"type": "DELETE_TRIGGER", "databaseName": "test", "collectionName": "joinMe", "name": "auditBatchWrites"}
```

Scheduled procedures

A schedule runs a stored procedure on a clock. Like a trigger it runs with its installer's authority
and needs `MANAGE` to install; unlike a trigger it may open its own `db.transaction`. Delivery is
at-most-once per due instant: missed runs while the node was down are skipped, not caught up.

```json
{"type": "CREATE_COLLECTION", "databaseName": "test", "collectionName": "heartbeat"}
```

```json
{"type": "SAVE_PROCEDURE", "databaseName": "test", "name": "beat", "script": "import db from \"db\";\nimport args from \"args\";\nconst prev = db.findById(db.name, 'heartbeat', 'beats');\nconst n = prev === null ? 1 : prev.n + 1;\ndb.save(db.name, 'heartbeat', { _id: 'beats', n: n, label: args.label });\nreturn n;"}
```

Every two seconds — `heartbeat` climbs from the next tick on, so the read below is `404-2` until the
first run lands

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds", "procedureName": "beat", "intervalMs": 2000, "args": {"label": "interval"}}
```

```json
{"type": "FIND_BY_ID", "databaseName": "test", "collectionName": "heartbeat", "_id": "beats"}
```

Every night at 03:00, in the configured `scriptTimeZone`, with its own run timeout

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "nightlyRollup", "procedureName": "beat", "cron": "0 3 * * *", "args": {"label": "nightly"}, "timeoutMs": 60000, "description": "daily rollup"}
```

Other cron forms — every 15 minutes, weekday mornings, the first of every month

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "everyQuarterHour", "procedureName": "beat", "cron": "*/15 * * * *", "args": {"label": "quarter"}}
```

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "weekdayMornings", "procedureName": "beat", "cron": "30 8 * * MON-FRI", "args": {"label": "weekday"}}
```

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "monthly", "procedureName": "beat", "cron": "0 0 1 * *", "args": {"label": "monthly"}}
```

List them — `nextRunAt` and `owner` are this node's view; `args` is omitted

```json
{"type": "LIST_SCHEDULES", "databaseName": "test"}
```

Re-saving replaces the schedule and bumps its `version`; `ifVersion` is refused when stale → `409-8`

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds", "procedureName": "beat", "intervalMs": 5000, "args": {"label": "slower"}}
```

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds", "procedureName": "beat", "intervalMs": 5000, "ifVersion": 99}
```

Disable one without deleting it

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds", "procedureName": "beat", "intervalMs": 5000, "enabled": false}
```

Exactly one of `cron` and `intervalMs` is required → `400-16`

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "neither", "procedureName": "beat"}
```

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "both", "procedureName": "beat", "cron": "0 3 * * *", "intervalMs": 1000}
```

A malformed cron is refused at save time → `400-16`

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "badCron", "procedureName": "beat", "cron": "not a cron"}
```

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "badCron", "procedureName": "beat", "cron": "99 * * * *"}
```

A procedure that does not exist → `404-8`

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "dangling", "procedureName": "neverInstalled", "intervalMs": 1000}
```

Deleting a procedure a schedule still references is refused → `400-16`

```json
{"type": "DELETE_PROCEDURE", "databaseName": "test", "name": "beat"}
```

A database holding `scheduleMaxPerDatabase` schedules (100 by default) refuses a new one → `400-17`.
Re-saving one it already holds is still allowed. To try it by hand, set `scheduleMaxPerDatabase=1` in
`lwnrdb.cfg` before running this section: `everyTwoSeconds` is then the only schedule it can hold, the other
saves above are refused, and so is this one.

```json
{"type": "SAVE_SCHEDULE", "databaseName": "test", "name": "oneTooMany", "procedureName": "beat", "intervalMs": 60000}
```

The schedule counters appear in the stats

```json
{"type": "GET_DATABASE_STATS"}
```

Delete — idempotent, so sending it twice still returns OK

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds"}
```

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "everyTwoSeconds"}
```

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "nightlyRollup"}
```

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "everyQuarterHour"}
```

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "weekdayMornings"}
```

```json
{"type": "DELETE_SCHEDULE", "databaseName": "test", "name": "monthly"}
```

Answers only a cluster gives

These need `clusterEnabled=true` and at least three nodes. A client may connect to any node: writes and
reads are forwarded to the collection's owner, admin operations to the admin coordinator.

- `503-2` — the node cannot see a majority of the cluster, so it refuses writes and admin operations. Stop
  two nodes of three and write to the survivor.
- `503-3` — the write committed on its owner, but not enough replicas acknowledged it within
  `replicationAckTimeoutMs`. It is not rolled back; anti-entropy brings the replicas up to date.
- `503-4` — the owner could not be reached, and the request provably never reached it.
- `503-8` — a forwarded write whose owner did not report an outcome. It may already have applied, so check
  before retrying.
- `503-7` — the same for a script placed on another node.
- `503-5` — the admin coordinator is still catching up with its peers (right after it starts, or while a
  peer holds a newer admin epoch). Retry shortly.
- `503-9` — no admin coordinator could be resolved from this node's view of the cluster.
- `503-12` — another admin operation still holds the coordinator's admin lane past `adminLaneTimeoutMs`.
- `503-10` — the collection exists on the cluster but has not reached this node yet.
- `421-1` — the node stopped owning the collection while the write waited for its lock, or an admin
  operation reached a node that is no longer the coordinator. Retry, and it is routed to the new one.

Every `500-*` answer is a failure inside the server — a disk that is full or unwritable, a file that could not
be read — and cannot be provoked from this playbook; the server log names the cause. One of them changes what a
client does next: `500-33` is a commit that failed after its commit point. The transaction stays open and keeps
its locks, and re-sending `COMMIT_TRANSACTION` finishes it once the fault is gone.
