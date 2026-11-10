# Protocol contract

The machine-readable contract the LWNRDB clients are tested against.

## Catalogue files

| File | Holds | Source |
|---|---|---|
| `operations.json` | Every client-facing operation: scope, admin only, allowed inside a transaction | `OperationType`, `AuthorizationChecker.isAdminOnly`, `TransactionOperationHelper.isAllowedDuringTransaction` |
| `error-codes.json` | Every error code: name, status, default message, retryable | `ErrorCode`, `Globals.RETRYABLE_ERROR_CODES` |
| `custom-types.json` | The custom value types, their predicate and ranking operators, the detection regex, the geo comparators | `CustomTypeFactory`, `Globals.CUSTOM_JSON_REGEX`, `GeoDistanceComparator` |

They are generated, never edited by hand. After changing the server's enums, regenerate them from the repository
root:

```bash
mvn test -Dtest=ProtocolCatalogueTest -Dlwnrdb.protocol.regenerate=true
```

Without the flag the test fails whenever a file differs from what the server would generate.

## Fixtures

`fixtures/requests/<area>.json` and `fixtures/responses/<area>.json` hold named cases:

```json
{
  "area": "data",
  "cases": [
    {"name": "data.find_by_id", "request": {"type": "FIND_BY_ID", "databaseName": "shop", "collectionName": "orders", "_id": "o-1"}}
  ]
}
```

A response case is `{"name", "frame", "expect"}`, where `expect` is `ok`, `push` (a LISTEN update), `listenEnded`
(`410-1`), `serverClosed` (a `CLOSE_CONNECTION` frame the client did not ask for) or `error:<code>`.

- Case names are unique across all files.
- The server's `ClientFixturesTest` parses and validates every request case through `RequestParser` and
  `RequestValidator`, and fails when a client-facing operation, a custom type, a custom operator or a geo comparator
  has no case.
- Every client keeps a table from case name to the builder call that produces it, and fails when a case is unmapped.
  Requests are compared as JSON trees, numbers by value.

A request the server cannot parse is answered like any other refusal, with `400-43` (`errors.400_43*`); its `type`
is `null` when the parser never reached the request's `type`. Clients must still survive a reply line that is not
JSON: fail only the oldest pending request and keep the connection.

## Adding an operation

1. Add the operation on the server and regenerate the catalogue.
2. Add request cases (and response cases for its reply and errors) here.
3. Implement it in each client, mapping the new case names.
