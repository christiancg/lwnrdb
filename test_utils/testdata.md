# Test Data

Manual test playbook — send each JSON command to the server over TCP, one line at a time.

Authenticate (must be done before any protected operation)

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "adminstrator"}
```

Create database

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

Aggregation with filter step matching string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "EQUALS", "field": "fieldAdded", "value": "an added field"}}]}
```

Aggregation with filter step not matching string

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "FILTER", "operator":{"fieldOperatorType": "NOT_EQUALS", "field": "fieldAdded", "value": "an added field"}}]}
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

Add field "average" -> type "AVG"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "average", "condition": null, "operator": {"type":"AVG", "operands": ["aNumber", 20]}}]}]}
```

Add field "sum" -> type "SUM"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "sum", "condition": null, "operator": {"type":"SUM", "operands": ["aNumber", 100]}}]}]}
```

Add field "subs" -> type "SUBS"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "subs", "condition": null, "operator": {"type":"SUBS", "operands": ["aNumber", 100]}}]}]}
```

Add field "max" -> type "MAX"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "max", "condition": null, "operator": {"type":"MAX", "operands": ["aNumber", 7]}}]}]}
```

Add field "min" -> type "MIN"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "min", "condition": null, "operator": {"type":"MIN", "operands": ["aNumber", 7]}}]}]}
```

Add field "multiply" -> type "MULTIPLY"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "multiply", "condition": null, "operator": {"type":"MULTIPLY", "operands": ["aNumber", 2]}}]}]}
```

Add field "divided" -> type "DIVIDE"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "divided", "condition": null, "operator": {"type":"DIVIDE", "operands": ["aNumber", 2]}}]}]}
```

Add field "powered" -> type "POW"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "powered", "condition": null, "operator": {"type":"POW", "operands": ["aNumber", 2]}}]}]}
```

Add field "rooted" -> type "ROOT"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "rooted", "condition": null, "operator": {"type":"ROOT", "operands": ["aNumber", 2]}}]}]}
```

Add field "absolute" -> type "ABS"

```json
{"type": "SAVE", "databaseName": "test", "collectionName": "testCollection", "object": { "aNumber": -3 }}
```

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "absolute", "condition": null, "operator": {"type":"ABS", "operand": "aNumber"}}]}]}
```

Add field "size" -> type "SIZE"

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

Add field "concatenated" -> type "CONCAT"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "concatenated", "condition": null, "operator": {"type":"CONCAT", "operands": ["aString", 73, "-birras", false, "aNumber"]}}]}]}
```

Add field "casted" -> type "CAST"

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

Add field "addedAfterMap" with condition "AND" -> type "MULTIPLY"

```json
{"type": "AGGREGATE", "databaseName": "test", "collectionName": "testCollection", "aggregationSteps": [{"type": "MAP", "operators": [{"fieldName": "addedAfterMap", "condition": {"conjunctionType":"AND","operators": [{"fieldOperatorType": "SMALLER_THAN_EQUALS", "field": "aNumber", "value": 10},{"fieldOperatorType": "GREATER_THAN", "field": "aNumber", "value": 7}]}, "operator": {"type":"MULTIPLY", "operands": ["aNumber", 3]}}]}]}
```

Add field "addedAfterMap" with condition "OR" -> type "MULTIPLY"

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

Drop index

```json
{"type": "DROP_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aString"}
```

```json
{"type": "DROP_INDEX", "databaseName": "test", "collectionName": "testCollection", "fieldName": "aNumber"}
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

Users and permissions

Re-authenticate after reconnecting

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "adminstrator"}
```

Create a non-admin user with read-write access to one database

```json
{"type": "CREATE_USER", "username": "alice", "password": "secret1234", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}}
```

Create a read-only user scoped to a single collection

```json
{"type": "CREATE_USER", "username": "readonly", "password": "readonly1234", "admin": false, "globalPermissions": [], "databasePermissions": {}, "collectionPermissions": {"test|testCollection": "READ"}}
```

Create a user that can create and drop databases

```json
{"type": "CREATE_USER", "username": "dbadmin", "password": "dbadmin1234", "admin": false, "globalPermissions": ["CREATE_DATABASE", "DROP_DATABASE"], "databasePermissions": {}, "collectionPermissions": {}}
```

Grant alice admin rights and update her permissions

```json
{"type": "CHANGE_PERMISSIONS", "username": "alice", "admin": true, "globalPermissions": ["CREATE_DATABASE", "DROP_DATABASE"], "databasePermissions": {}, "collectionPermissions": {}}
```

Downgrade alice back to a regular user

```json
{"type": "CHANGE_PERMISSIONS", "username": "alice", "admin": false, "globalPermissions": [], "databasePermissions": {"test": "READ_WRITE"}, "collectionPermissions": {}}
```

Authenticate as alice

```json
{"type": "AUTHENTICATE", "username": "alice", "password": "secret1234"}
```

Authenticate back as admin

```json
{"type": "AUTHENTICATE", "username": "admin", "password": "adminstrator"}
```

Delete alice

```json
{"type": "DELETE_USER", "username": "alice"}
```

Delete readonly

```json
{"type": "DELETE_USER", "username": "readonly"}
```

Delete dbadmin

```json
{"type": "DELETE_USER", "username": "dbadmin"}
```

Admin-only: get memory & schema stats (heap usage, cache usage vs cap, OS free RAM,

totals across databases/collections, plus per-collection page/index/entry breakdown)

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

