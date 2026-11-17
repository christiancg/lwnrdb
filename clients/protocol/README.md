# Protocol contract

The machine-readable contract the LWNRDB clients are tested against. The readable version, with the framing, how
replies are matched, the retry rules and every operation, is the [wire protocol reference](../../docs/protocol.md).

## Catalogue files

| File | Holds |
|---|---|
| `operations.json` | Every client-facing operation, its scope, whether it is admin only and whether it is allowed inside a transaction |
| `error-codes.json` | Every error code with its name, status, default message and whether it is retryable |
| `custom-types.json` | The custom value types, their operators, the regex that detects them and the geo comparators |

They are generated from the server's code and never edited by hand. After changing an operation, an error code or a
custom type on the server, regenerate them from the repository root:

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
(`410-1`), `serverClosed` (a `CLOSE_CONNECTION` the client didn't ask for) or `error:<code>`.

- Case names are unique across all files.
- The server parses and validates every request case, and fails when a client-facing operation, a custom type, a
  custom operator or a geo comparator has no case.
- Every client maps each case name to the builder call that produces it, and fails when a case is unmapped. Requests
  are compared as JSON trees, numbers by value. Custom values (`#geo(...)`, `#vector(...)`, `#datetime(...)`,
  `#time(...)`) are compared by what they parse to, since each language writes numbers its own way (`#geo(1,2)` from
  JavaScript, `#geo(1.0,2.0)` from Java) and the server reads both.

A request the server can't parse is refused with `400-43`, and its reply's `type` is `null` when the parser never got
to the request's `type`. A client still has to survive a reply line that isn't JSON: it fails only the oldest pending
request and keeps the connection.

## Adding an operation

1. Add the operation on the server and regenerate the catalogue.
2. Add request cases here, and response cases for its reply and errors.
3. Implement it in each client and map the new case names.
