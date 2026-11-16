# LWNRDB wire protocol

This is everything a client and the server say to each other: framing, authentication, what requests and replies look
like, how replies are matched to requests and which errors are safe to retry. After that come every operation, the
permission model and every error code.

If you only want to use the database from an application, take one of the [client libraries](../clients/README.md),
which already implement all of this. This page is for writing or debugging a client, or for talking to the server by
hand. The machine-readable side of it is [`clients/protocol`](../clients/protocol/README.md): the operation, error-code
and custom-type catalogues generated from the server, and the request and response fixtures every official client is
tested against.

- [Transport](#transport)
- [Session lifecycle](#session-lifecycle)
- [Request envelope](#request-envelope)
- [Response envelope](#response-envelope)
- [Matching replies to requests](#matching-replies-to-requests)
- [Values](#values)
- [Retrying](#retrying)
- [Transactions on the wire](#transactions-on-the-wire)
- [Versioning](#versioning)
- [Writing a client: checklist](#writing-a-client-checklist)
- [Naming rules](#naming-rules)
- [Operations](#operations)
- [Schema validation](#schema-validation)
- [Users & Permissions](#users--permissions)
- [Permission model](#permission-model)
- [Fault injection](#fault-injection-testing-and-debugging-only)
- [Error codes](#error-codes)

## Transport

A client opens a TCP connection to the server's `port` (default `8989`), plain or TLS when the server runs with
`tlsEnabled=true` (see [TLS / secure connections](operations.md#tls--secure-connections)). A TLS server refuses
plaintext at the handshake.

Both directions carry UTF-8 text, one JSON object per line, each line ended by `\n`. A `\r` right before the `\n` is
stripped, and a lone `\r` is just part of the line. There is no length prefix, no request id and no handshake other
than `AUTHENTICATE`.

### Connection limits

| Limit | Default | Past it |
|---|---|---|
| Line size once authenticated (`maxRequestSize`) | `64Mb` | `400-22`, then the connection is closed |
| Line size before authenticating | `64Kb` | `400-22`, then the connection is closed |
| JSON tokens per line: one per 4 bytes of the line limit | | `400-43`, the connection stays open |
| Time to authenticate (`authTimeoutMs`) | `10000` | `408-4`, then the connection is closed |
| Unauthenticated connections per address (`maxUnauthenticatedConnectionsPerAddress`) | `16` | `503-14`, then the connection is closed |
| Invalid requests before authenticating (`maxUnauthenticatedInvalidRequests`) | `5` | closed after the last answer |
| Client connections (`maxConnections`, `0` for none) | `100` | `503-1`, then the connection is closed |

- An oversized line is refused as soon as it passes the limit, without waiting for the newline, because the rest of it
  can't be skipped safely.
- The authentication deadline is absolute: traffic and failed attempts don't extend it. With TLS the handshake runs
  under the same deadline, and a TLS connection over `maxConnections` or the per-address limit is closed before its
  handshake, with no frame.
- Real documents, vectors and pipelines average well above 4 bytes per token. Only something like a huge flat
  `[1,1,1,...]` hits the token limit.
- Once authenticated, a connection is never closed for being idle, unless it holds an open transaction (see
  [Transactions](#transactions)).

A client that stops reading never blocks anyone else, but the server won't wait for it forever:

- A response write that moves less than 64Kb in `clientWriteStallTimeoutMs` (default `30000`) closes the connection,
  which rolls back its open transaction. Reading a large response a few kilobytes at a time counts as stalled.
- When the unread replies pass `clientOutboundQueueMaxBytes` (default `16Mb`), the backlog is dropped, the connection's
  listens end, a `CLOSE_CONNECTION` frame with `413-1` is queued and the connection is closed. A single response bigger
  than the cap still goes out when nothing is waiting behind it.
- Across all connections, unsent responses are capped by `maxUnsentResponseBytes` (default a quarter of the heap). A
  read whose response is 64Kb or more and would pass it is answered `503-17` instead, which is safe to retry.

When the server closes a connection on its own it sends one `CLOSE_CONNECTION` frame with the reason first:

```jsonl
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The request exceeds the maximum allowed size","errorCode":"400-22"}
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The connection did not authenticate in time","errorCode":"408-4"}
```

## Session lifecycle

1. Connect. The connection counts against `maxConnections`, and against the per-address limit until it authenticates.
2. Send [`AUTHENTICATE`](#authenticate) within `authTimeoutMs`. Before that only `AUTHENTICATE`, `CLOSE_CONNECTION` and
   [`SERVER_INFO`](#server_info) are answered, and anything else gets `401-1`.
3. Send requests, one per line. You can send several before reading the replies.
4. Send [`CLOSE_CONNECTION`](#close_connection), which is answered with
   `{"type":"CLOSE_CONNECTION","status":"OK","message":"Bye!","errorCode":null}` before the socket closes, or just close
   the socket.

Everything a session holds ends with its connection: the authenticated user, the open transaction (rolled back) and
every `LISTEN` registration. None of it can be handed to another connection.

## Request envelope

A request is one JSON object with a `type` naming the operation (the full list is in
[`operations.json`](../clients/protocol/operations.json)). Operations on a database carry `databaseName`, operations on
a collection carry `databaseName` and `collectionName`, and each operation adds its own fields.

```json
{"type":"FIND_BY_ID","databaseName":"shop","collectionName":"orders","_id":"o-1"}
```

- Fields the operation doesn't know are ignored.
- A request may nest objects and arrays at most 128 levels deep.
- A line that is not a JSON object, names no known `type`, nests too deep or has too many tokens is answered `400-43`
  (with `"type": null` when the `type` couldn't be read) and the connection stays open.

## Response envelope

Every reply is one JSON object with `type` (the operation it answers), `status`, `message` and `errorCode`, plus the
operation's own fields:

```json
{"_id":"o-1","inserted":true,"type":"SAVE","status":"OK","message":"Successfully saved","errorCode":null}
{"type":"FIND_BY_ID","status":"NOT_FOUND","message":"Entry not found","errorCode":"404-2"}
```

| `status` | Meaning |
|---|---|
| `OK` | Done; `errorCode` is `null` |
| `ERROR` | Refused or failed |
| `NOT_FOUND` | What it names doesn't exist |
| `UNAUTHENTICATED` | The connection isn't authenticated, or its user no longer exists |
| `FORBIDDEN` | The user lacks the permission it needs |

- `errorCode` is `null` on success and `NNN-N` otherwise: an HTTP-style range and a sequential number, like `401-1`.
  4xx are client errors, 5xx server errors, and 503 means the server can't serve it right now. Branch on `errorCode`,
  never on `message`, which may carry details and may change. Every code is in [Error codes](#error-codes).
- Every field is written, `null` ones included, so a missing field never means `null`. The examples on this page often
  leave the `null` ones out.
- Fields come in no fixed order.
- Three empty results are errors on the wire: `FIND_BY_ID` answers `404-2` for a missing id, `AGGREGATE` answers `404-3`
  when nothing matches and `LIST_USERS` answers `404-5` when no user matches. A client turns exactly those three into an
  empty result. Any other `NOT_FOUND` is a real error.

## Matching replies to requests

The server answers the requests of one connection in the order it received them, one reply each. So a client keeps a
first-in, first-out list of the requests it's waiting on and hands each reply to the oldest. Two kinds of frame are not
replies and have to be taken out before looking at that list:

- `LISTEN` pushes: a frame with `type` `LISTEN` that carries `"isUpdate": true` (new results) or `"errorCode": "410-1"`
  (the server ended the listen). It belongs to the registration named by its `listenId`, and it can arrive between any
  two replies, even before or after the reply to the request that caused it. The reply to `LISTEN` itself has
  `"isUpdate": false`, and a refused `LISTEN` has no `listenId`, so both are ordinary replies.
- An unrequested `CLOSE_CONNECTION`: when the server closes a connection on its own (`400-22`, `408-4`, `503-1`,
  `503-14`, `413-1`) it writes one such frame and closes the socket. Every request still waiting has failed, and
  whether it ran is unknown. The server also closes right after an ordinary reply, with no extra frame, after an
  `AUTHENTICATE` answered `401-4` and after the last allowed invalid request before authenticating.

Two more rules keep the list aligned:

- A reply line that isn't JSON fails only the oldest waiting request. The server doesn't send one, but a client should
  survive it and keep the connection.
- A request that times out on the client must close its connection. The server will still answer it, and that late
  reply would otherwise go to the next request.

## Values

Documents and request fields are plain JSON, with these rules:

- Numbers are IEEE-754 doubles. An integer above 2^53 loses precision, so send it as a string. A number outside the
  double range (`1e400`) is refused with `400-43`.
- `_id` is a string of 1-64 letters, digits, `_` and `-`, and it is compared exactly (case-sensitive) everywhere. Every
  other string comparison is case-insensitive. A `SAVE` without one gets a UUID, returned in the reply's `_id`.
- Custom values travel as strings matching `^#[a-zA-Z0-9]{3,20}\(.*\)$` (the `detectionRegex` in
  [`custom-types.json`](../clients/protocol/custom-types.json)):

  | Text | Value |
  |---|---|
  | `#datetime(2024-01-01T10:00)` | An ISO-8601 local date-time, seconds and fractions optional, with no zone or offset |
  | `#time(09:30)` | An ISO-8601 local time |
  | `#geo(40.71,-74.0)` | A point: latitude -90 to 90, longitude -180 to 180 |
  | `#vector(0.12,0.44,0.91)` | A dense vector of numbers |

  The server parses such a string wherever a document or an operand holds one and writes it back in the same form.
  Equal values compare equal whatever their spelling (`#geo(1,2)` equals `#geo(1.0,2.0)`). That also means a plain
  string matching the pattern can't be stored as text, so a client should refuse one instead of letting it change
  meaning. A zoned date-time has to be converted to local time by the client.

## Retrying

A client may resend a request on its own only when the error code is marked retryable: *yes* in the `Retryable`
column of [Error codes](#error-codes), `"retryable": true` in
[`error-codes.json`](../clients/protocol/error-codes.json). Each of those means nothing was applied. Back off between
attempts, most of them clear in milliseconds to seconds.

Never retry these automatically:

- `503-3`: the write committed, only its replication fell short of a quorum. Sending it again writes it again, which is
  harmless for a `SAVE` with an `_id` or a `DELETE`, but not for a `SAVE` without one or a read-modify-write script.
- `503-7` and `503-8`: the outcome is unknown. Only an idempotent request may be resent, and that's the caller's call.
- `500-33`, `500-34` and `409-10`: the write or transaction was logged or decided, and the server finishes it.
- Anything inside a transaction (see [Transactions on the wire](#transactions-on-the-wire)).

After a lost connection, resend only requests that change nothing: `FIND_BY_ID`, `AGGREGATE`, the `LIST_*` and `GET_*`
operations and `SERVER_INFO`. Any write may have landed before the connection dropped. A resent `SAVE` with
`failOnUpdate` whose first attempt landed answers `409-18`, so after a dropped connection that code can mean your own
write.

## Transactions on the wire

A transaction lives on the connection that sent `START_TRANSACTION`. Every request of it, and the commit or rollback,
must go on that connection, and nothing else may go on it meanwhile. A connection pool hands that connection to the
transaction for its whole life. The rest of the rules are under [Transactions](#transactions); two matter most for a
client:

- A `COMMIT_TRANSACTION` answered `500-33` or `409-10` was decided, and the server finishes applying it. A
  `ROLLBACK_TRANSACTION` then answers `500-33` instead of undoing it. In a cluster, sending `COMMIT_TRANSACTION` again on
  the same connection finishes it (see [clustering.md](clustering.md)). Never send its writes again.
- A `COMMIT_TRANSACTION` whose reply never came has an unknown outcome. Close that connection without a rollback and
  read the data to find out.

## Versioning

`SERVER_INFO` tells any authenticated user the server's `protocolVersion`, which goes up with every change to the wire:
a request or reply field, an operation, an error code. It is `1` today. What may change between releases is in
[compatibility.md](compatibility.md).

## Writing a client: checklist

- One JSON object per line, UTF-8, `\n`-terminated, at most `maxRequestSize` bytes (64Kb before authenticating).
- Authenticate first, within `authTimeoutMs`. Authentication belongs to the connection.
- Match replies first-in, first-out, after taking out `LISTEN` pushes (`isUpdate: true` or `410-1`) and unrequested
  `CLOSE_CONNECTION` frames.
- Close a connection whose request timed out. Fail only the oldest request on a non-JSON reply line.
- Branch on `errorCode`, never on `message`. Read `404-2`, `404-3` and `404-5` as empty results.
- Retry only the retryable codes, never inside a transaction, and after a lost connection only reads.
- Pin a transaction to one connection, and keep `LISTEN` registrations off pooled connections, since a registration
  ends with its connection.
- Write custom values in their `#type(...)` form and refuse plain strings that match it.
- Test against [`clients/protocol`](../clients/protocol/README.md): produce every request fixture from your public API
  and decode every response fixture.

## Naming rules

- Database and collection names: 3-64 letters, digits, `_` and `-`. `admin` and `admin_pages` are reserved database
  names and `script_runs` a reserved collection name, in any case (`Admin` is refused too).
- `_id`: 1-64 letters, digits, `_` and `-`.
- Indexed field names: 1-64 characters from `A-Z a-z 0-9 _ . -`, where a dot addresses a nested field
  (`address.city`). Other fields can't be indexed but can still be queried, with a scan.
- Names must differ by more than case, because they end up as file names and some filesystems ignore case.
  `CREATE_DATABASE`, `CREATE_COLLECTION`, `CREATE_INDEX`, `SAVE_PROCEDURE` and `SAVE_SCHEDULE` answer `409-11` when a
  sibling differs from the new name only by case, on every filesystem.

## Operations

### `LIST_DATABASES`
```json
{"type":"LIST_DATABASES"}
```
An admin sees every database. Anyone else sees the ones they own or hold any grant on (database, one of its
collections, or a script level other than `NONE`).

### `CREATE_DATABASE`
```json
{"type":"CREATE_DATABASE","databaseName":"my_db"}
```
The creator becomes its first owner. Refused with `400-41` once the server holds `maxDatabases` databases.

### `DROP_DATABASE`
```json
{"type":"DROP_DATABASE","databaseName":"my_db"}
```
Needs admin or ownership. Drops everything stored with the database, procedures and schedules included.

### `LIST_COLLECTIONS`
```json
{"type":"LIST_COLLECTIONS","databaseName":"my_db"}
```

### `CREATE_COLLECTION`
```json
{"type":"CREATE_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```
Refused with `400-40` once the database holds `maxCollectionsPerDatabase` collections. Creating one that already exists
is always allowed.

### `DROP_COLLECTION`
```json
{"type":"DROP_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```
Drops its documents, indexes, schema and triggers.

### `SAVE` (insert or update)
Saves one document. With an `_id` that exists it replaces that document, with one that doesn't it creates it, and
without an `_id` it creates one under a UUID. The reply carries the `_id` and `inserted`.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"name":"Alice"}}
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"_id":"user-1","name":"Alice"}}
```
The `_id` may also go beside `object`. When both are given, the one inside `object` wins.

With `"failOnUpdate": true` the save only inserts: if a document with that `_id` exists it is refused with `409-18`
and nothing is written.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","failOnUpdate":true,"object":{"_id":"user-1","name":"Alice"}}
{"type":"SAVE","status":"ERROR","message":"A document with this _id already exists","errorCode":"409-18"}
```
- A refused insert runs no hook, fires no trigger and notifies no `LISTEN`. A schema violation is still reported first,
  as `400-7`.
- Inside a transaction its own view decides: an id it saved is refused, an id it deleted can be inserted again. The
  refusal leaves the transaction usable.
- It can't be combined with a `condition` (`400-1`), and `BULK_SAVE` doesn't take it.
- The clients call it `insert`, and scripts `db.insert`.

With a `condition` instead of an `_id`, `SAVE` updates every matching document. The condition is a filter operator,
the same one an `AGGREGATE` `FILTER` step takes. `object` is merged into each match: its top-level properties are added
or replaced and the rest of the document is kept. A nested object replaces the stored one whole, a `null` stores
`null`, and nothing is removed.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll",
 "condition":{"conjunctionType":"AND","operators":[
   {"fieldOperatorType":"EQUALS","field":"status","value":"pending"},
   {"fieldOperatorType":"SMALLER_THAN","field":"createdAt","value":1700000000000}]},
 "object":{"status":"expired","expiredBy":"sweeper"}}
```
```json
{"type":"SAVE","status":"OK","message":"Updated 12 document(s)","updatedCount":12}
```
- It never inserts. A `condition` together with an `_id`, or with an empty `object`, is refused with `400-1`. Matching
  nothing is `OK` with `updatedCount` 0.
- It's all or nothing. Every merged document is checked against the schema (`400-7`) and `maxEntrySize` before anything
  is written, and readers never see half of it. More than `updateMaxMatches` matches (default `100000`) is refused with
  `400-45`.
- `UPDATED` before triggers run on every merged document first, and one rejection refuses the whole request
  (`400-21`). After triggers fire per document or once per request, depending on their `mode`.
- The condition has the same limits as a filter: `400-27`, `408-6` and `503-20`. A `script` operator in it runs while
  the collection's write lock is held.
- Inside a transaction the condition sees the transaction's own writes, and every match counts against
  `transactionMaxOps` and `transactionMaxBytes` (`400-26`).

### `BULK_SAVE`
Saves several documents (at least one) with the same rules as `SAVE`. The same `_id` twice in one request is `400-3`,
and a schema violation refuses the whole batch.
```json
{"type":"BULK_SAVE","databaseName":"my_db","collectionName":"my_coll","objects":[{"name":"Alice"},{"_id":"user-2","name":"Bob"}]}
```

### `FIND_BY_ID`
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```
Answers `404-2` when there is no such document. Like `AGGREGATE`, it accepts `"dirtyRead": true` to skip the collection
lock (see [Concurrency & locking](../README.md#concurrency--locking)).

### `DELETE`
Takes either an `_id` or a `condition`. Both, or neither, is `400-1`.
```json
{"type":"DELETE","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```
With `_id` it deletes that document and answers `404-2` when there is none.

With `condition` it deletes every matching document. The condition is the same filter operator an
[`AGGREGATE`](#aggregate) `FILTER` step takes, with the same indexes and limits (`400-27`, `408-6`, `503-20`).
```json
{"type":"DELETE","databaseName":"my_db","collectionName":"my_coll",
 "condition":{"conjunctionType":"AND","operators":[
   {"fieldOperatorType":"EQUALS","field":"status","value":"archived"},
   {"fieldOperatorType":"SMALLER_THAN","field":"updatedAt","value":1700000000000}]}}
```
```json
{"type":"DELETE","status":"OK","message":"Deleted 42 document(s)","deletedCount":42}
```
- It's all or nothing, and readers never see half of it. More than `deleteMaxMatches` matches (default `100000`) is
  refused with `400-44` before anything is deleted. Matching nothing is `OK` with `deletedCount` 0.
- `DELETED` before triggers run on every match first, and one rejection refuses the whole request (`400-21`). After
  triggers fire per document or once per request.
- A `script` operator in the condition runs while the collection's write lock is held, so other writers wait for it.
- Inside a transaction the condition sees the transaction's own writes, and every match counts against
  `transactionMaxOps` and `transactionMaxBytes` (`400-26`).

### `AGGREGATE`
Every query except a lookup by id is a pipeline of steps. An empty `aggregationSteps` returns every document. Optional
top-level fields are `"dirtyRead": true` (see [Concurrency & locking](../README.md#concurrency--locking)) and
`"analyze": true` (see [Explain / Analyze](#explain--analyze)).

```json
{
  "type": "AGGREGATE",
  "databaseName": "my_db",
  "collectionName": "my_coll",
  "aggregationSteps": [
    {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"status","value":"active"}},
    {"type":"SORT","fieldName":"name","ascending":true},
    {"type":"LIMIT","limit":10}
  ]
}
```

| Step | Fields | Notes |
|---|---|---|
| `FILTER` | `operator` | A field, conjunction, custom or script operator |
| `MAP` | `operators` (not empty) | See [`MAP` operators](#map-operators) |
| `GROUP_BY` | `fieldName` | Emits `{"<field>": key, "group": [...]}`, nested when the field is dotted |
| `JOIN` | `joinCollection`, `localField`, `remoteField`, `asField` | Needs `READ` on `joinCollection` too |
| `COUNT` | | Returns `{"count": N}` |
| `DISTINCT` | `fieldName` (optional) | Without it, deduplicates whole documents |
| `LIMIT` | `limit` (> 0) | |
| `SKIP` | `skip` (>= 0) | |
| `SORT` | `fieldName`, `ascending` | |
| `REDUCE` | `script` | Folds the stream into one document; optional `initialValue` (default `null`) and `resultField` (default `value`) |

A dotted field name works the same way whether a step reads it or writes it, so a later step finds what an earlier one
wrote. When a step writes a nested path through a value that isn't an object, the output gets an object there (the
stored document is never touched).

Limits:

| Limit | Default | Error |
|---|---|---|
| Run time (`aggregateTimeoutMs`, `0` for none) | `300000` | `408-6` |
| Operands of one `IN`/`NOT_IN` list, `within` polygon or conjunction, operator nodes of one `FILTER`, operators and operands of one `MAP` (`maxFilterOperands`) | `10000` | `400-27` |
| Bytes one pipeline builds or holds: `MAP` strings and script values, `JOIN` arrays, rows held by `GROUP_BY`, an unbounded `SORT`, `JOIN` and `DISTINCT`, and the result rows (`aggregateMaxResultBytes`) | `auto`, a quarter of the heap | `400-36` |
| The same bytes across every running pipeline (`aggregateTotalHeldBytes`) | `auto`, a quarter of the heap | `503-20`, retryable |
| One `JOIN` row's joined array (`maxEntrySize`) | | `400-28` |

A `MAP` `fieldName` may nest at most 128 levels. `LISTEN` re-runs and reads inside a transaction have the same limits.

Result order: a pipeline whose last order-determining step is a `SORT` is fully ordered. Otherwise the order is
unspecified, so a `LIMIT` or `SKIP` without a `SORT` returns an unspecified subset, like SQL's `LIMIT` without
`ORDER BY`. `REDUCE` folds in a fixed order so its value doesn't change between runs: as the first step it folds by page
and then `_id`, after other steps by `_id`, and rows without an `_id` (from `GROUP_BY` or `DISTINCT`) follow in a fixed
order of their content. Put a `SORT` before it to choose the order. A `GROUP_BY` group and a `JOIN`'s joined array list
their documents by `_id`.

Indexes: a `FILTER` at the start of the pipeline uses every index it can. `GROUP_BY`, `SORT` and `DISTINCT` use one
only as the first step, and then only for scalar values, so a document whose field holds an object or array doesn't
show up in their index-backed results. A `JOIN` uses the index of the remote field wherever it sits. Object and array fields have hash indexes that only serve `EQUALS`, `NOT_EQUALS`,
`IN` and `NOT_IN` on the whole value.

Field operator types: `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`,
`SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`. Every one needs a `value`; a null operand is written
`"value": null`, and a missing `value` is `400-1`.

Conjunction operator types: `AND`, `OR`, `NOR`, `XOR`, `NAND`, with their operands in `operators`.

How values compare:

- Strings compare case-insensitively, except `_id`, which compares exactly, and `CONTAINS` on a string, which is a
  case-sensitive substring test.
- Custom values compare by meaning (`#datetime(2024-01-01T10:00)` equals `#datetime(2024-01-01T10:00:00)`), numbers by
  value, and objects and arrays as whole values, element by element. Inside an object or array a string compares
  exactly, and so does a string element for `CONTAINS` on an array.
- `null` only takes part in equality. `EQUALS null` matches documents whose field is `null`, `NOT_EQUALS null` those
  whose field isn't. Against a non-null operand a `null` field matches only `NOT_EQUALS`. In an `IN`/`NOT_IN` list,
  `null` is a value like any other. A document without the field is excluded by every operator.
- `SORT` uses the same comparison. Custom types sort by their own order (chronologically for `#datetime` and `#time`,
  by geohash for `#geo`, by SimHash signature for `#vector`). Equal values tie and break on `_id`. Mixed types rank
  boolean, number, string, custom.

### `MAP` operators

Each entry of a `MAP` step's `operators` has a `fieldName`, an optional `operator` and an optional `condition` (a filter
operator). With an `operator` the step sets the field to its result, without one it removes the field, and with a
`condition` it only does so on the documents that match.

```json
{"type":"MAP","operators":[
  {"fieldName":"total","operator":{"type":"MULTIPLY","operands":["price","qty"]}},
  {"fieldName":"label","operator":{"type":"CONCAT","operands":["name","- / ","status"]}},
  {"fieldName":"secret"}]}
```

| `type` | Takes | Result |
|---|---|---|
| `SUM`, `SUBS`, `MULTIPLY`, `DIVIDE`, `POW`, `ROOT`, `AVG`, `MAX`, `MIN` | `operands` | Arithmetic over field paths and numbers |
| `ABS`, `SIZE` | `operand` | Absolute value, or the size of an array |
| `CONCAT` | `operands` | One string; see below |
| `CAST` | `fieldName`, `toType`, and `customTypeName` for `JSON_CUSTOM` | The field converted; see below |
| `SCRIPT` | `script` | See [Script operators](#script-operators-simplejs-in-the-pipeline) |

`CONCAT`: a top-level string operand is a field path unless it starts with `-`, which marks a literal. A string inside
an array operand is always a literal. A `null`, or a field the document doesn't have, adds the text `null`. The result
may be at most `maxEntrySize` characters, and the strings and script values one `MAP` step adds to one document may
total at most `maxEntrySize` bytes. Past either the pipeline stops with `400-28`.

`CAST` sets the field to `null` whenever it can't produce a value of the target type:

- `NUMBER` accepts only JSON number syntax. Anything else, surrounding whitespace, `Infinity`, `NaN` or an overflowing
  exponent gives `null`.
- `BOOLEAN` accepts `true` and `false` in any case. A number casts to `value != 0`.
- `STRING` writes a number the way documents are written, and a custom value as its data.
- `JSON_CUSTOM` gives `null` when the string isn't a valid value of the named type.

### Script operators (SimpleJS in the pipeline)

A pipeline can run JavaScript in three places. All three need `scriptsEnabled` and a script grant on the database
(admin, owner or `scriptPermissions` of at least `RUN`). A `READ` grant isn't enough. They are refused inside a
`LISTEN` (`400-19`).

- A filter operator, `(doc) => boolean`, recognised by a `script` key. It can go anywhere a field operator can.
  ```json
  {"type":"FILTER","operator":{"script":"export default (doc) => doc.price * doc.qty > 100;"}}
  ```
- A `MAP` mid-operator, `(doc) => value`. An `undefined` result leaves the field unset.
  ```json
  {"type":"MAP","operators":[
    {"fieldName":"total","operator":{"type":"SCRIPT","script":"export default (doc) => doc.price * doc.qty;"}}]}
  ```
- A `REDUCE` step, `(accumulator, doc) => accumulator`, which collapses the stream to one document. Later steps work on
  that document.
  ```json
  {"type":"REDUCE","resultField":"total","initialValue":0,
   "script":"export default (acc, doc) => acc + doc.price * doc.qty;"}
  ```

The function is the module's `export default`, or else its top-level `return`. Async and generator functions are
refused. A pipeline script is a pure function: it has no `db` module, no `fetch`, no procedure imports and no
`importText`.

Its limits apply to the whole query, not to each document: `aggregationScriptInstructionBudget` (`400-11`) and
`aggregationScriptTimeoutMs` (`408-1`). `aggregationScriptMaxSourceBytes` caps one operator's source (`400-10`), and a
script that throws gives `400-9`. A script filter never uses an index, so put it after an indexed `FILTER`. Inside a
conjunction the other operands still use their indexes and the script only sees what they let through.

### Custom operators (type-specific)

A filter operator can also be a custom operator, recognised by `customOperatorName`. It can go anywhere a field
operator can, including inside a conjunction.

The geo type (`#geo(lat,lng)`) has two:

- `distance` compares the great-circle distance in metres between the field and the point in `value` against
  `distance`, with a `comparator` (`SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS` or
  `EQUALS`).
  ```json
  {"type":"FILTER","operator":{"customOperatorName":"distance","field":"location",
    "value":"#geo(40.71,-74.0)","comparator":"SMALLER_THAN","distance":1000}}
  ```
- `within` matches points inside the polygon formed by at least three points in `polygon`.
  ```json
  {"type":"FILTER","operator":{"customOperatorName":"within","field":"location",
    "polygon":["#geo(40.0,-74.2)","#geo(40.9,-74.2)","#geo(40.9,-73.7)","#geo(40.0,-73.7)"]}}
  ```

An index on a geo field serves `within` and `distance` with a `SMALLER_THAN*` comparator, and every candidate is checked
exactly against the document. `distance` with `GREATER_THAN*` scans.

The vector type (`#vector(...)`) has one ranking operator:

- `nearest` returns the `k` documents whose vector is most similar to `value` by cosine similarity, most similar first.
  ```json
  {"type":"FILTER","operator":{"customOperatorName":"nearest","field":"embedding",
    "value":"#vector(0.12,0.44,0.91)","k":10,"exact":false}}
  ```

With an index on the field, `nearest` is approximate: it looks at the vectors with a similar SimHash signature and can
miss a true neighbour. `"exact": true` scans the collection and always returns the real top `k`. A `COUNT` after
`nearest` returns `min(k, matches)`.

### Explain / Analyze

Send `"analyze": true` on an `AGGREGATE` to get an `analyzeResult` next to the `results`. It needs no extra permission
and only applies to `AGGREGATE`. In analyze mode an empty result is `OK` with the analysis, not `404-3`.

```json
{
  "type": "AGGREGATE",
  "status": "OK",
  "results": [ "..." ],
  "analyzeResult": {
    "startTime": 1750000000000,
    "endTime": 1750000000012,
    "durationMillis": 12,
    "indexUsed": true,
    "indexesUsed": ["status"],
    "documentsScanned": 42,
    "locksAcquired": ["my_db|my_coll", "my_db|my_coll|status"],
    "suggestions": []
  }
}
```

| Field | Meaning |
|---|---|
| `startTime`, `endTime`, `durationMillis` | When the pipeline ran, in epoch milliseconds, after parsing and authorization |
| `indexUsed`, `indexesUsed` | Whether an index was used, and on which fields |
| `documentsScanned` | Documents read while running the pipeline |
| `locksAcquired` | Collection locks (`db\|coll`) and field-index locks (`db\|coll\|field`); no collection lock for a dirty read |
| `suggestions` | Advice: create an index when none was used, or move a `FILTER` to the front |

A pipeline with script operators also reports `scriptInvocations` and `scriptMillis`.

### `CREATE_INDEX`
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldNames":["email","status","age"]}
```
`fieldName` and `fieldNames` can be combined. Every field that isn't indexed yet is built in one pass over the
collection, and fields already indexed are skipped (the message says which). If every field is already indexed it
answers `OK` without rebuilding anything.

One invalid, blank or reserved name, or an empty `fieldNames`, refuses the whole request with `400-1`, as does a name
that differs from another only by case (`409-11`) or going past `maxIndexesPerCollection` (`400-42`). A missing
collection is `404-11` (`503-10` on a cluster node it hasn't reached yet).

### `DROP_INDEX`
```json
{"type":"DROP_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

### `REINDEX`
Rebuilds field indexes from the documents, for when an index went stale. Without `fieldNames` it rebuilds every index
of the collection. Needs `READ_WRITE`.
```json
{"type":"REINDEX","databaseName":"my_db","collectionName":"my_coll","fieldNames":["email"]}
{"type":"REINDEX","status":"OK","message":"Rebuilt 1 index(es)","rebuiltFields":["email"]}
```
A named field without an index is `404-6`. Admin databases can't be reindexed. In a cluster every node rebuilds, and
the answer is `503-3` when a majority didn't confirm (the rebuild stands where it ran).

`"primaryKey": true` also rebuilds the primary-key index from the page files, then the page metadata. It's the repair
for a primary-key index that can't be read (every request on the collection fails until then) or disagrees with the
pages.
```json
{"type":"REINDEX","databaseName":"my_db","collectionName":"my_coll","primaryKey":true}
{"type":"REINDEX","status":"OK","message":"Rebuilt the primary-key index (2 entries)","rebuiltFields":[],"primaryKeyEntries":2,"unversionedEntries":0}
```
- Each entry keeps the write version the old index had for it. An id the old index didn't know, or every id when it
  couldn't be read, gets version `0` and is counted in `unversionedEntries`. In a cluster any other node's copy wins over
  those.
- Field indexes aren't rebuilt unless `fieldNames` names them. The changed ids are fixed in the background and indexed
  reads stay correct meanwhile. When the old index couldn't be read, every field index is rebuilt and listed in
  `rebuiltFields`.
- It refuses with `409-15`, and changes nothing, when a page holds something that isn't a record, the same `_id` twice
  or a torn last record, or when an unfinished page rewrite is still on disk (a restart recovers that one).

### `SAVE_SCHEMA`
Sets the collection's JSON Schema (draft 2020-12), replacing any previous one. Every later `SAVE` and `BULK_SAVE`
document must comply (see [Schema validation](#schema-validation)). Needs admin or ownership.
```json
{
  "type": "SAVE_SCHEMA",
  "databaseName": "my_db",
  "collectionName": "my_coll",
  "schema": {
    "type": "object",
    "required": ["name", "age"],
    "properties": {
      "name": {"type": "string", "minLength": 1},
      "age": {"type": "integer", "minimum": 0},
      "location": {"customType": "geo"}
    },
    "additionalProperties": false
  }
}
```
```json
{"type":"SAVE_SCHEMA","status":"OK","message":"Collection schema saved successfully","warnings":[]}
```
`warnings` lists keywords that were ignored. Nothing is stored when it's refused:

- `400-8`: not a valid 2020-12 schema, or uses a keyword that isn't supported.
- `400-24`: more than 100,000 schema nodes once its `$ref`s are followed.
- `400-39`: more than `schemaMaxPatterns` regular expressions (default `256`).
- `400-31`: a regular expression longer than `schemaMaxPatternLength` (default `4096`).

### `DELETE_SCHEMA`
Removes the collection's schema. Answers `OK` whether or not there was one. Needs admin or ownership.
```json
{"type":"DELETE_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```

### `GET_SCHEMA`
Returns the schema as it was saved. Needs `READ`.
```json
{"type":"GET_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
{"type":"GET_SCHEMA","status":"OK","message":"Ok","schema":{"type":"object","required":["_id"]}}
```
Answers `404-12` when there is no schema, `404-11` when the collection doesn't exist, and `503-11` when the stored
schema can't be read.

### `LISTEN`
Subscribes to the results of a query. The server runs it once and answers with the results, a `listenId` and a
`resultHash`. Whenever the collections it reads change, the query runs again in the background, and if the hash changed
the new results are pushed on the same connection.

It takes the same `aggregationSteps` as `AGGREGATE` and needs `READ` on every collection it reads.

```json
{
  "type": "LISTEN",
  "databaseName": "my_db",
  "collectionName": "my_coll",
  "aggregationSteps": [
    {"type": "FILTER", "operator": {"fieldOperatorType": "EQUALS", "field": "status", "value": "active"}}
  ]
}
```
The reply has `"isUpdate": false`, and every push `"isUpdate": true`:
```json
{"type":"LISTEN","status":"OK","message":"Listening for changes","listenId":"550e8400-e29b-41d4-a716-446655440000",
 "results":["..."],"resultHash":"a3f5...d91e","isUpdate":false}
{"type":"LISTEN","status":"OK","message":"Query results updated","listenId":"550e8400-e29b-41d4-a716-446655440000",
 "results":["..."],"resultHash":"b7c2...f308","isUpdate":true}
```
When the server ends a listen on its own, because a collection it reads or its database was dropped or the user can no
longer read it, it pushes one last frame and nothing after it:
```json
{"type":"LISTEN","status":"NOT_FOUND","errorCode":"410-1","message":"The listen ended: a collection it reads was dropped",
 "listenId":"550e8400-e29b-41d4-a716-446655440000"}
```
`STOP_LISTEN` or closing the connection ends a listen without that frame.

The re-runs use dirty reads, have their own timeout (`listenRerunTimeoutMs`, default `10000`; a re-run past it pushes
nothing) and are shared between identical registrations of the same user.

| Limit | Default | Error |
|---|---|---|
| Registrations per connection (`maxListensPerConnection`) | `64` | `400-25` |
| Registrations on the server (`maxListens`) | `10000` | `400-25` |
| Registrations per user, across connections (`maxListensPerUser`, `0` for none) | `256` | `400-38` |
| Size of one `LISTEN` line (`listenMaxRequestBytes`) | `1Mb` | `400-37` |
| Size of every kept `LISTEN` request together (`listenMaxRetainedBytes`) | `64Mb` | `503-15` |

### `STOP_LISTEN`
```json
{"type":"STOP_LISTEN","listenId":"550e8400-e29b-41d4-a716-446655440000"}
```
Answers `404-7` when the listen isn't registered.

### Transactions

A connection can have one transaction open at a time. While it's open, its `SAVE`, `BULK_SAVE` and `DELETE` are
buffered instead of applied, and its own `FIND_BY_ID` and `AGGREGATE` see them. Other connections don't. The first
buffered write to a collection locks that collection until the commit or rollback, so no other connection can read or
write it meanwhile (see [Concurrency & locking](../README.md#concurrency--locking)).

- Only `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID`, `AGGREGATE`, `COMMIT_TRANSACTION`, `ROLLBACK_TRANSACTION`,
  `SERVER_INFO` and `CLOSE_CONNECTION` are accepted. Anything else is `409-6`.
- If a write can't lock its collection within `transactionLockTimeoutMs`, it answers `409-5` and the transaction is
  aborted: its writes are discarded and its locks released. Every later request but `ROLLBACK_TRANSACTION` answers
  `409-9`, so a retried write never runs outside the transaction the client thinks it's in.
- Closing the connection rolls the transaction back.
- `COMMIT_TRANSACTION` checks the user's grants again. If one was revoked meanwhile, the commit is refused with `403-1`
  and the whole transaction is rolled back.

Idle and old transactions: if no complete request arrives within `transactionIdleTimeoutMs` (default `30000`), the
server rolls the transaction back and keeps the connection open. The next request, whatever it is, is answered `409-16`
once and not run; only `CLOSE_CONNECTION` and `SERVER_INFO` are answered normally. A transaction older than
`transactionMaxAgeMs` (default `600000`, `0` for none) is rolled back the same way however busy it is, and the next
request gets `408-5`. A long-running request is never cut short, and a transaction that is already aborted or whose
commit is half applied (`500-33`) is never rolled back this way.

```json
{"type":"SAVE","status":"ERROR","message":"The transaction was rolled back after being idle too long","errorCode":"409-16"}
```

Size: one transaction may buffer at most `transactionMaxOps` operations (default `100000`, one per document of a
`BULK_SAVE` or per match of a conditional `SAVE` or `DELETE`) and `transactionMaxBytes` of documents (default `256Mb`).
The write that would pass either is refused with `400-26`, buffers nothing, and the transaction stays usable. All open
transactions together may buffer at most `transactionTotalMaxBytes` (default `auto`, a quarter of the heap); past it a
write gets `503-21`, which is safe to retry.

#### `START_TRANSACTION`
```json
{"type":"START_TRANSACTION"}
{"type":"START_TRANSACTION","status":"OK","message":"Transaction started","transactionId":"<uuid>"}
```
Answers `409-3` when a transaction is already open on the connection.

#### `COMMIT_TRANSACTION`
Applies the buffered writes in order and releases the locks.
```json
{"type":"COMMIT_TRANSACTION"}
```

#### `ROLLBACK_TRANSACTION`
Discards the buffered writes and releases the locks.
```json
{"type":"ROLLBACK_TRANSACTION"}
```
`COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` answer `409-4` when no transaction is open.

#### `LIST_TRANSACTIONS` (admin only)
Lists the distributed transactions that are stuck across the cluster: prepared but never resolved because their
coordinator was lost. It asks every live node and merges the answers. On a standalone server it shows the local view.
```json
{"type":"LIST_TRANSACTIONS"}
```
```json
{"type":"LIST_TRANSACTIONS","status":"OK","transactions":[
  {"dtxId":"<uuid>","coordinator":"10.0.0.11:9990","coordinatorReachable":false,
   "participants":["10.0.0.12:9990","10.0.0.13:9990"],"ageMs":42000,
   "perNodeStatus":{"10.0.0.12:9990":"PREPARED","10.0.0.13:9990":"PREPARED"}}]}
```

#### `RESOLVE_TRANSACTION` (admin only)
Forces the outcome of a stuck transaction on every node. `decision` is `commit` or `abort`. Only use it once you know
the right outcome (see [docs/clustering.md](clustering.md)).
```json
{"type":"RESOLVE_TRANSACTION","dtxId":"<uuid>","decision":"commit"}
{"type":"RESOLVE_TRANSACTION","status":"OK","message":"Transaction committed"}
```
A missing `dtxId` or another `decision` is `400-1`.

### `RUN_SCRIPT`
Runs a JavaScript program inside one database, the request's `databaseName`. The engine and everything a script can
use are described in [docs/simplejs.md](simplejs.md). Needs admin, ownership or `scriptPermissions` of at least `RUN`
on the database, and answers `403-2` when the server runs with `scriptsEnabled=false`.

```json
{
  "type": "RUN_SCRIPT",
  "databaseName": "mydb",
  "script": "import db from \"db\";\nimport args from \"args\";\nconst u = db.findById(db.name, \"users\", args.userId);\nconsole.log(`found ${u._id}`);\nreturn { name: u.name };",
  "args": { "userId": "u1" }
}
```
```json
{
  "type": "RUN_SCRIPT",
  "status": "OK",
  "message": "Script executed successfully",
  "result": {"name": "Alice"},
  "logs": ["found u1"],
  "logsTruncated": false,
  "runId": "3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83",
  "metrics": {
    "instructions": 4211, "instructionBudget": 10000000,
    "peakMemoryBytes": 18240, "memoryBudget": 67108864,
    "dbOperations": 3, "durationMs": 12
  }
}
```

- `script` is required. `args` is any object, read with `import args from "args"`. `db.name` is the database the
  script runs in.
- `result` is the top-level `return`, else `export default`, else an object of the named exports, else `null`. A
  returned promise is awaited, and one that never settles fails the run with `400-20`.
- `logs` keeps the newest `scriptMaxLogLines` lines of `console` output, each cut to `scriptMaxLogLineChars`, and
  `logsTruncated` says whether anything was dropped. `metrics` shows the usage next to each budget. On a failure,
  `stack` lists the frames the error came from, innermost first. All of them come back on every outcome, failures
  included.
- `runId` identifies the run, so a slow one can be found with [`LIST_SCRIPTS`](#list_scripts-admin-only) and stopped
  with [`CANCEL_SCRIPT`](#cancel_script-admin-only).
- Every operation the script sends is authorized again against the caller's own permissions, and the schema still
  applies to its writes. A script can't leave its database.
- The `db` module can `findById`, `aggregate`, `cursor`, `save`, `bulkSave`, `delete`, `listCollections`,
  `listDatabases` and run a `transaction`. Nothing else is reachable: no DDL, no user management. A failed operation
  throws a catchable `Error`.
- A script can import any stored procedure of its database with `import ... from "procedures/<name>"`.
- `fetch` reaches public hosts only, unless the operator lists others in `scriptFetchAllowlist`.
- Finished runs are recorded in the database's `script_runs` collection, which you can query with `AGGREGATE` (see
  [docs/simplejs.md](simplejs.md) for what is recorded).
- In a cluster the script may run on another node, chosen by load, and its operations are routed to each collection's
  owner.

Errors:

| Code | Why |
|---|---|
| `403-2` | Scripting is disabled |
| `403-1` | The caller may not run scripts here |
| `404-4` | Unknown database |
| `400-10` | Source over `scriptMaxSourceBytes` |
| `400-9` | The script threw or didn't parse; `message` is `"<ErrorName>: <message>"` |
| `400-11` | Instruction or depth budget exceeded |
| `400-12` | Over `scriptMaxMemoryBytes` |
| `400-15` | Result over `scriptMaxResultBytes` |
| `400-30` | An array-like past `scriptMaxArrayLength` |
| `400-20` | The returned promise never settled |
| `408-1` | Over `scriptTimeoutMs` |
| `408-2` | Cancelled |
| `503-6` | A concurrency limit refused it; the message says which (`node`, `user` or `database`). Nothing ran |
| `503-7` | It was placed on another node that never reported back. It isn't run again here, because it may have run there |
| `409-6` | Sent with a transaction open |

### `SAVE_PROCEDURE`
Stores a named script in a database so it can be called by name. Needs admin, ownership or `scriptPermissions` of
`MANAGE`. The source is parsed when it's saved, so a broken procedure is refused here and not on somebody's first call.
Saving an existing name replaces it and raises its `version`.

```json
{
  "type": "SAVE_PROCEDURE",
  "databaseName": "mydb",
  "name": "recalcTotals",
  "script": "import db from \"db\";\nimport args from \"args\";\nconst o = db.findById(db.name, \"orders\", args.id);\nreturn o.qty * o.price;",
  "description": "optional",
  "enabled": true,
  "ifVersion": 3
}
```
```json
{"type":"SAVE_PROCEDURE","status":"OK","message":"Procedure saved successfully","version":4}
```

- `enabled` defaults to `true`. A disabled procedure can't be called (`404-8`).
- `ifVersion` is optional optimistic locking: when it doesn't match the stored version the save is refused with
  `409-8`. `0` means the procedure must not exist yet.
- A procedure that exports instead of returning is a library other scripts can import. Imports must resolve when the
  procedure is saved (`400-18`), so install libraries first. A caller without `MANAGE` can run imported code but can't
  read its source.
- While a trigger, before hook or schedule of another user runs the procedure, directly or through imports, only that
  user, an owner or an admin can change it (`403-4`). Otherwise that user's trigger would run code they never saw.

Errors: `403-2` scripting disabled, `403-1` not allowed, `403-4` bound by another user's trigger or schedule, `404-4`
unknown database, `400-1` an invalid name (same rules as a collection) or an empty script, `400-10` source too big,
`400-13` doesn't parse (the message has the line and column), `400-18` an import that doesn't resolve, `400-34` more
than `maxProceduresPerDatabase`, `409-8` version conflict.

### `DELETE_PROCEDURE`
```json
{"type":"DELETE_PROCEDURE","databaseName":"mydb","name":"recalcTotals"}
```
Answers `OK` whether or not the procedure existed. Refused with `400-14` while a trigger uses it and `400-16` while a
schedule does (the message names it), and with `403-4` under the same rule as `SAVE_PROCEDURE`.

### `LIST_PROCEDURES`
```json
{"type":"LIST_PROCEDURES","databaseName":"mydb","includeSource":false}
```
```json
{
  "type": "LIST_PROCEDURES",
  "status": "OK",
  "procedures": [
    {"name":"recalcTotals","sourceHash":"9f2...","version":4,"enabled":true,
     "createdAt":1756100000000,"updatedAt":1756100500000,"updatedBy":"alice"}
  ]
}
```
Needs `READ` on the database. `sourceHash` only shows for callers who could read the source (`MANAGE`, owner or admin).
`"includeSource": true` adds the `source` and needs the same, or it answers `403-6`.

### `CALL_PROCEDURE`
Runs a stored procedure. Same permission as [`RUN_SCRIPT`](#run_script), same sandbox, same reply and the same errors,
plus `404-8` when the procedure doesn't exist or is disabled.
```json
{"type":"CALL_PROCEDURE","databaseName":"mydb","procedureName":"recalcTotals","args":{"id":"o1"}}
{"type":"CALL_PROCEDURE","status":"OK","message":"Procedure executed successfully","result":42,"logs":[],"logsTruncated":false,"runId":"3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"}
```
The procedure runs with the caller's permissions, so calling one never grants more than the caller already has.

### `LIST_SCRIPTS` (admin only)
Lists every script running anywhere in the cluster right now: `RUN_SCRIPT`, `CALL_PROCEDURE`, triggers and schedules. A
node that doesn't answer is left out (and logged) instead of failing the listing.
```json
{"type":"LIST_SCRIPTS"}
```
```json
{"type":"LIST_SCRIPTS","status":"OK","message":"Ok","scripts":[
  {"runId":"3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83","node":"10.0.0.2:8990","kind":"CALL_PROCEDURE",
   "database":"shop","name":"reprice","username":"alice","ageMs":18244}]}
```
`kind` is `RUN_SCRIPT`, `CALL_PROCEDURE`, `TRIGGER` or `SCHEDULE`. `name` is the procedure, trigger or schedule, `null`
for `RUN_SCRIPT`. `username` is whose permissions the run has (the caller, or the definer of a trigger or schedule), and
`node` is where it runs.

### `CANCEL_SCRIPT` (admin only)
Stops the run with that `runId`, wherever it runs.
```json
{"type":"CANCEL_SCRIPT","runId":"3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"}
{"type":"CANCEL_SCRIPT","status":"OK","message":"Ok","cancelled":true}
```
- A `runId` that matches nothing answers `OK` with `cancelled: false`. A missing or malformed `runId` is `400-1`.
- The run stops at its next instruction check. It can't catch the cancellation and its `finally` blocks don't run. A run
  waiting on a host call stops when that call returns.
- The cancelled caller gets `408-2`, and an open `db.transaction` is rolled back.
- A cancelled trigger run is dropped, not retried.

### `LIST_TRIGGER_RUNS` (admin only)
Lists the trigger runs still recorded across the cluster: pending ones, and dead ones that used up their attempts.
```json
{"type":"LIST_TRIGGER_RUNS","status":"DEAD"}
```
```json
{"type":"LIST_TRIGGER_RUNS","status":"OK","message":"Ok","runs":[
  {"runId":"8f0e...","node":"10.0.0.4:8990","status":"DEAD","database":"shop","collection":"orders",
   "trigger":"auditOrders","procedure":"audit","event":"UPDATED","attempts":3,
   "lastError":"TypeError: Cannot read properties of null","ageMs":91234,"nextAttemptAt":0}]}
```
The request's `status` (optional) narrows it to `PENDING` or `DEAD`, and any other value is `400-1`. A `PENDING` run is
normally gone quickly. A `DEAD` one waits for an operator.

### `RESOLVE_TRIGGER_RUN` (admin only)
Replays or discards one recorded trigger run, wherever it lives.
```json
{"type":"RESOLVE_TRIGGER_RUN","runId":"8f0e...","decision":"replay"}
{"type":"RESOLVE_TRIGGER_RUN","status":"OK","message":"Ok","resolved":true}
```
- `decision` is `replay` or `discard`. Anything else, or a missing `runId`, is `400-1`.
- `replay` starts the attempt count over. `discard` drops the run for good.
- A `runId` no node holds answers `OK` with `resolved: false`.
- A run whose documents no longer exist is discarded instead of replayed.

### `SAVE_TRIGGER`
Runs a stored procedure around writes to a collection: after they commit (the default) or before, where it can refuse
or rewrite the document. Needs admin, ownership, or `MANAGE` together with `READ_WRITE` on the collection (through its
own collection grant if it has one, otherwise the database grant), and the same goes for `DELETE_TRIGGER` and
`TEST_TRIGGER`. Triggers only fire when the server runs with `triggersEnabled=true`, but they can be managed either way.

```json
{
  "type": "SAVE_TRIGGER",
  "databaseName": "mydb",
  "collectionName": "orders",
  "name": "auditWrites",
  "events": ["CREATED", "UPDATED", "DELETED"],
  "procedureName": "recalcTotals",
  "timing": "after",
  "mode": "document",
  "allowCascade": false,
  "enabled": true,
  "ifVersion": 1
}
```
```json
{"type":"SAVE_TRIGGER","status":"OK","message":"Trigger saved successfully","version":2,"definer":"alice"}
```

- `timing` is `after` (the default) or `before`. Before triggers are described [below](#before-triggers).
- An after trigger runs asynchronously once the write has committed, so it can't refuse or change the write, and its
  failures never reach the writer.
- It runs with the permissions of whoever saved it (its `definer`), not the writer's. Saving it again makes the saving
  user the definer. If the definer is deleted or loses script `RUN` on the database or `READ` on the collection, the
  trigger is disabled (`LIST_TRIGGERS` shows `enabled: false` and a `disabledReason`) until it's saved again.
- `mode` is `document` (one run per document, the default) or `batch` (one run per `BULK_SAVE`, or per collection and
  event for a transaction).
- `allowCascade` defaults to `false`, so writes made by a trigger fire nothing. With it on, a chain stops at
  `triggerMaxDepth`.
- A write inside a transaction fires when the transaction commits, so a rolled-back write fires nothing.
- The procedure gets `{event, database, collection, id, document, trigger, actingUser, definer, firedAt, depth}` as its
  `args`. In `batch` mode `documents` replaces `id` and `document`. `firedAt` stays the same on every retry.
- A failed run is retried with a growing backoff up to `triggerMaxAttempts`, then kept as a dead run for
  [`LIST_TRIGGER_RUNS`](#list_trigger_runs-admin-only) and [`RESOLVE_TRIGGER_RUN`](#resolve_trigger_run-admin-only). The
  server log has its stack.
- Dropping a collection or database discards its pending and dead runs.
- There's no dry run for an after trigger. Keep the logic in a procedure and test it with
  [`CALL_PROCEDURE`](#call_procedure).

Errors: `403-1` not allowed, `403-5` replacing another user's trigger, `404-4` unknown collection, `404-8` unknown or
disabled procedure, `400-14` no events, an unknown event or an unknown mode, `400-32` more than
`triggerMaxPerCollection` triggers, `409-8` version conflict.

#### Before triggers

A before trigger runs on the writing request, inside the collection's write lock, before the document is written. Use
it to refuse a write or compute a field on the server.

```js
export default function (document, context) {
    if (!document.customerId) {
        throw new Error("customerId is required");
    }
    if (context.event === "DELETED") {
        return;
    }
    return { ...document, total: document.qty * document.price };
}
```

The procedure is called once per document with `(document, context)`, where `context` is
`{event, database, collection, trigger, actingUser, firedAt, id}`. What it returns decides the write:

| Returned | Outcome |
|---|---|
| `undefined`, `null` or `true` | The write goes ahead unchanged |
| A plain object | The write goes ahead with that document instead |
| Anything else (number, string, array, `false`) | Refused with `400-21` |
| A thrown error | Refused with `400-21`, with the error's message |
| A sandbox limit | Refused with `408-1`, `400-11` or `400-12` |
| A [`CANCEL_SCRIPT`](#cancel_script-admin-only) | Refused with `408-2` |

- Every failure refuses the write, timeouts included.
- A replacement can't change the `_id` and is validated against the schema again. On a `DELETED` event there's nothing
  to replace, so returning a document is an error.
- There is no `db` module, but the procedure can import other procedures. That is also why `mode: "batch"` and
  `allowCascade` are refused on a before trigger, and why deleting its definer doesn't disable it.
- Several before triggers on one collection run in name order, each one getting the previous one's output. The first
  refusal stops the chain.
- The instruction budget (`beforeHookInstructionBudget`) and timeout (`beforeHookTimeoutMs`) cover the whole request,
  so all the documents of a `BULK_SAVE` share them, and the first refusal fails the whole `BULK_SAVE`.
- A running before trigger shows up in `LIST_SCRIPTS` and can be cancelled. It doesn't count against the script
  concurrency limits.
- Its `console` output is dropped. Use [`TEST_TRIGGER`](#test_trigger) to see it.
- In a cluster it runs on the collection's owner.

### `TEST_TRIGGER`
Runs a before trigger against a document you send and tells you what it would do, without writing anything. Same
permission as `SAVE_TRIGGER`. It runs on the node you're connected to.
```json
{
  "type": "TEST_TRIGGER",
  "databaseName": "mydb",
  "collectionName": "orders",
  "name": "normalizeOrder",
  "event": "CREATED",
  "document": {"_id": "o1", "qty": 2, "price": 10}
}
```
```json
{"type":"TEST_TRIGGER","status":"OK","message":"Trigger tested successfully","decision":"replace",
 "document":{"_id":"o1","qty":2,"price":10,"total":20},"logs":["checking o1"],"logsTruncated":false}
```
- `decision` is `accept`, `replace` (with the resulting `document`) or `reject` (with a `reason` and a `stack`). A
  rejection is still an `OK` reply. A trigger that couldn't run at all answers the error the write would have got.
- `console` output comes back in `logs`.

Errors: `403-2` scripting disabled, `403-1` not allowed, `404-4` unknown collection, `404-9` unknown trigger, `404-8`
unknown or disabled procedure, `400-14` an unknown event, an event the trigger doesn't watch, or an after trigger.

### `DELETE_TRIGGER`
```json
{"type":"DELETE_TRIGGER","databaseName":"mydb","collectionName":"orders","name":"auditWrites"}
```
Answers `OK` whether or not the trigger existed. Only its definer, an owner or an admin can delete or replace a trigger
someone else installed (`403-5`).

### `LIST_TRIGGERS`
```json
{"type":"LIST_TRIGGERS","databaseName":"mydb","collectionName":"orders"}
```
Without `collectionName` it lists every trigger of the database. Each entry has its `collectionName` and `definer`.
Needs `READ` on the database.

### `SAVE_SCHEDULE`
Runs a stored procedure on a clock. Needs admin, ownership or `MANAGE`. A schedule touches no collection by itself, so
no data grant is needed: every `db` call its procedure makes is checked against the definer's grants. Answers `403-2`
when the server runs with `schedulesEnabled=false`, which also stops installed schedules from firing. Saving an
existing name replaces it and raises its `version`.

```json
{
  "type": "SAVE_SCHEDULE",
  "databaseName": "mydb",
  "name": "nightlyRollup",
  "procedureName": "rollup",
  "cron": "0 3 * * *",
  "args": {"days": 1},
  "timeoutMs": 60000,
  "description": "optional",
  "enabled": true,
  "ifVersion": 2
}
```
```json
{"type":"SAVE_SCHEDULE","status":"OK","message":"Schedule saved successfully","version":3}
```

- Give exactly one of `cron` and `intervalMs`. `intervalMs` fires every so many milliseconds. `cron` is the usual five
  fields (`minute hour day-of-month month day-of-week`) with `*`, values, ranges, `*/n` and `a-b/n` steps, lists and
  three-letter names (`JAN`, `MON`; `7` is Sunday). When both day fields are set they are OR-ed. It's evaluated in
  `scriptTimeZone`, so it means the same instant on every node.
- It runs with its definer's permissions, like a trigger. Only the definer, an owner or an admin can replace or delete
  it (`403-5`). If the definer is deleted or loses script `RUN`, the schedule is disabled with a `disabledReason` until
  it's saved again.
- `args` is handed to the procedure, as with [`CALL_PROCEDURE`](#call_procedure). One procedure can have several
  schedules with different `args`.
- `timeoutMs` bounds one run, and `0` or missing means `scheduleTimeoutMs`. It can't exceed `scheduleMaxTimeoutMs`
  (default `300000`).
- `enabled` defaults to `true`. `ifVersion` works as in `SAVE_PROCEDURE`.
- A run isn't transactional unless the procedure opens its own `db.transaction`.
- It fires at most once per due time. A run still going when the next one is due skips that one, and runs missed while
  the node was down are skipped, not caught up. In a cluster one node owns each schedule, but around a membership
  change it can fire twice or skip a tick (see [docs/clustering.md](clustering.md)).
- When a run can't get a script slot, that occurrence is skipped and recorded as `SKIPPED` in `script_runs`.

Errors: `403-2` schedules disabled, `403-1` not allowed, `403-5` replacing another user's schedule, `404-4` unknown
database, `404-8` unknown procedure, `400-1` an invalid name, `400-16` neither or both of `cron` and `intervalMs`, a bad
cron or a negative `timeoutMs`, `400-17` more than `scheduleMaxPerDatabase`, `400-29` `timeoutMs` over
`scheduleMaxTimeoutMs`, `400-33` more than `scheduleMaxTotalPerUser` for the user across every database, `400-35`
firing more often than `scheduleMinIntervalMs` allows, `409-8` version conflict.

### `DELETE_SCHEDULE`
```json
{"type":"DELETE_SCHEDULE","databaseName":"mydb","name":"nightlyRollup"}
```
Answers `OK` whether or not the schedule existed. Only its definer, an owner or an admin can delete someone else's
(`403-5`).

### `LIST_SCHEDULES`
```json
{"type":"LIST_SCHEDULES","databaseName":"mydb"}
```
```json
{
  "type": "LIST_SCHEDULES",
  "status": "OK",
  "schedules": [
    {"name":"nightlyRollup","procedureName":"rollup","cron":"0 3 * * *","intervalMs":0,
     "timeoutMs":60000,"enabled":true,"definer":"ops","version":3,
     "createdAt":1756000000000,"updatedAt":1756600000000,"updatedBy":"ops",
     "nextRunAt":1756699200000,"owner":"node-2"}
  ]
}
```
Needs `READ` on the database. `args` is left out unless the request sets `"includeArgs": true`, which needs `MANAGE`,
ownership or admin (`403-6` otherwise). `nextRunAt` and `owner` are the answering node's view, and `owner` is missing
when clustering is off.

### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

## Schema validation

A collection can have one JSON Schema (draft 2020-12) that every write must satisfy. Set it with
[`SAVE_SCHEMA`](#save_schema) and remove it with [`DELETE_SCHEMA`](#delete_schema). With a schema in place:

- A `SAVE` that doesn't comply is refused with `400-7` and nothing is written.
- A `BULK_SAVE` is all or nothing: one bad document refuses the whole batch with `400-7`, and the message names its
  `_id`.
- `_id` is never validated, so `"additionalProperties": false` doesn't need to list it.
- Validation has a step budget per request, `schemaValidationBudget` (default 5,000,000), shared by every document of a
  `BULK_SAVE`. A document that runs out is refused with `400-23`, which keeps a catastrophic pattern like `(x+x+)+y`
  from holding the collection forever. Keep it the same on every node of a cluster.

Supported keywords:

- any type: `type` (with `integer`), `enum`, `const`
- objects: `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `minProperties`,
  `maxProperties`, `dependentRequired`, `dependentSchemas`
- arrays: `prefixItems`, `items`, `minItems`, `maxItems`, `uniqueItems`, `contains`, `minContains`, `maxContains`
- strings: `minLength`, `maxLength`, `pattern`
- numbers: `minimum`, `maximum`, `exclusiveMinimum`, `exclusiveMaximum`, `multipleOf`
- combining: `allOf`, `anyOf`, `oneOf`, `not`, `if`, `then`, `else`
- local `$ref` (like `#/$defs/foo`) with `$defs`

Patterns use ECMA-262 syntax in Unicode mode, as 2020-12 says, the same as a JavaScript `RegExp` with the `u` flag.
Java-only syntax such as `a++` or `(?i)` is refused with `400-8`. `format` is accepted as an annotation only, and
metadata keywords (`$schema`, `$id`, `title`, `description`) are accepted. An unknown keyword is ignored and reported in
`warnings`. Keywords that are known but not implemented (`unevaluatedProperties`, `unevaluatedItems`, `$dynamicRef`,
remote `$ref`, `$vocabulary`) are refused with `400-8`, so nobody believes a constraint applies when it doesn't.

There's one extra keyword, `customType`, for the custom value types: `{"customType":"geo"}` requires a `#geo(...)`
value, and `vector`, `datetime` and `time` work the same way. Custom values also pass `"type":"string"`, since that's
how they travel.

In a cluster the schema is replicated to every node, and a node that was down when it changed catches up when it
rejoins.

## Users & Permissions

A connection has to authenticate before anything else. Only `AUTHENTICATE`, `CLOSE_CONNECTION` and `SERVER_INFO` work
without it.

### `AUTHENTICATE`
```json
{"type":"AUTHENTICATE","username":"Alice","password":"secret"}
```
A wrong username or password is `401-3`, and an unknown user takes as long to answer as a known one. Failed attempts
are slowed down, never locked out:

- After `k` failures in a row for a username from one address, the next attempt for it from that address waits
  `authFailureDelayBaseMs * 2^(k-1)` ms (250ms, 500ms, 1s... up to `authFailureDelayMaxMs`, 30s by default). A success
  clears it.
- The `maxFailedAuthAttempts`-th failure on one connection (default `5`, `0` for none) answers `401-4` and closes the
  connection.
- With `authVerifierCacheEnabled=true` (the default) a repeated login with the same password skips the expensive
  password hash. The first login of each user after a restart still pays it, so clients that open many short
  connections should pool them.

### `CREATE_USER` (admin only)
```json
{
  "type": "CREATE_USER",
  "username": "bob",
  "password": "secret1234",
  "admin": false,
  "globalPermissions": ["CREATE_DATABASE"],
  "databasePermissions": {"ordersDb": "READ_WRITE"},
  "collectionPermissions": {"analyticsDb|events": "READ"},
  "scriptPermissions": {"ordersDb": "MANAGE"}
}
```
Every permission field is optional. `collectionPermissions` keys are `database|collection`, and `scriptPermissions`
values are `NONE`, `RUN` or `MANAGE`. See [Permission model](#permission-model).

### `DELETE_USER` (admin only)
```json
{"type":"DELETE_USER","username":"bob"}
```
The last admin can't be deleted (`400-4`).

### `CHANGE_PERMISSIONS` (admin only)
Replaces all of the user's permissions. The last admin can't be demoted (`400-5`).
```json
{
  "type": "CHANGE_PERMISSIONS",
  "username": "bob",
  "admin": false,
  "globalPermissions": [],
  "databasePermissions": {"ordersDb": "READ"},
  "collectionPermissions": {},
  "scriptPermissions": {"ordersDb": "RUN"}
}
```

### `SET_PASSWORD`
| Field | Required | Notes |
|---|---|---|
| `username` | yes | The user whose password changes |
| `newPassword` | yes | At least 8 characters |
| `currentPassword` | when changing your own password as a non-admin | A wrong one is `400-6` |

```json
{"type":"SET_PASSWORD","username":"Alice","currentPassword":"old_pass","newPassword":"new_pass_1234"}
{"type":"SET_PASSWORD","username":"Alice","newPassword":"new_pass_1234"}
```
An admin can change anyone's password without `currentPassword`. A non-admin changing someone else's gets `403-1`,
whether or not that user exists.

A change signs the user out everywhere else: their other connections get `401-1` until they authenticate again. The
connection that changed its own password stays signed in, and when an admin changes someone's password every
connection of that user is signed out. In a cluster the other nodes do the same.

### `SET_DATABASE_OWNERS` (admin only)
Replaces the owners of a database. Every user must exist.
```json
{"type":"SET_DATABASE_OWNERS","databaseName":"my_db","owners":["Alice","bob"]}
```

### `GET_DATABASE_STATS`
Returns memory use, totals, and a breakdown per database and collection.
```json
{"type":"GET_DATABASE_STATS"}
```
```json
{"type":"GET_DATABASE_STATS","status":"OK","stats":{
  "memory":{"heapUsedBytes":123456789,"heapMaxBytes":6442450944,"heapCommittedBytes":268435456,
    "userCacheBytes":2097152,"residentTombstoneBytes":0,"maxMemoryBytes":536870912,
    "cachingDisabled":false,"cacheUnlimited":false},
  "triggers":{"enabled":true,"fired":128,"failed":0,"dropped":0,"queued":0},
  "scripts":{"routingEnabled":true,"running":2,"capacity":16,"available":14,"rejected":0,"waited":7,
    "forwarded":118,"forwardFallbacks":3,"outcomeUnknown":0,"localityWeight":50,"localityPreferred":47,"cancelled":1},
  "totals":{"userCount":3,"databaseCount":1,"collectionCount":2,"indexCount":1,"pageCount":4,
    "entryCount":5000,"sizeBytes":2560000},
  "databases":[{"name":"my_db","collectionCount":2,"indexCount":1,"pageCount":4,"entryCount":5000,"sizeBytes":2560000,
    "collections":[{"name":"my_coll","indexCount":1,"indexes":["email"],"pageCount":2,"entryCount":3000,
      "sizeBytes":1536000}]}]}}
```
`memory.userCacheBytes` is the cache size `maxMemory` is enforced against, and the `heap*` fields are the JVM's own.

Any authenticated user can send it, and the answer only covers what they can see. An admin gets everything. Anyone
else gets the databases [`LIST_DATABASES`](#list_databases) shows them, the collections in them they own or can read,
and `totals` over that, without `userCount`. The node-wide sections (`memory`, `inDoubtTransactions`, `triggers`,
`schedules`, `scripts`) are for admins only.

### Snapshots (`START_SNAPSHOT`, `READ_SNAPSHOT`, `RELEASE_SNAPSHOT`)

A snapshot is a consistent view of a database that stays readable while writes go on. It's what
[`--dump`](../README.md#client-shell-exec-dumprestore) uses. Any client may send these, but the
[client libraries](../README.md#client-libraries) don't expose them.

`START_SNAPSHOT` needs `READ` on the database and covers the collections the user can read (all of them, or those in
`collectionNames`). It waits at most `transactionLockTimeoutMs` for a point between transactions and answers `409-5`
past it, or `503-18` when a committed transaction is still being applied.
```json
{"type":"START_SNAPSHOT","databaseName":"shop","collectionNames":["orders","customers"]}
{"type":"START_SNAPSHOT","status":"OK","message":"Ok","errorCode":null,"snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10","collectionNames":["customers","orders"]}
```
`READ_SNAPSHOT` pages one collection in `_id` order, `limit` (1 to 100000) ids at a time after `afterId` (`null` for the
first page). A page can hold fewer documents than `limit` while `done` is `false`; the next page starts after `lastId`.
```json
{"type":"READ_SNAPSHOT","databaseName":"shop","collectionName":"orders","snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10","afterId":null,"limit":1000}
{"type":"READ_SNAPSHOT","status":"OK","message":"Ok","errorCode":null,"results":[{"_id":"o-1","total":12}],"lastId":"o-1","done":false}
{"type":"RELEASE_SNAPSHOT","databaseName":"shop","snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10"}
```
- A snapshot belongs to the connection that opened it. Another connection's id answers `404-13`.
- It's released by `RELEASE_SNAPSHOT`, by closing the connection, by not reading it for `snapshotIdleTimeoutMs`
  (default `300000`) and by a restart.
- At most `snapshotMaxOpen` (default `4`) can be open on a node. One more answers `503-22`.
- It's aborted when the old versions it keeps pass `snapshotMaxBytes` (default `1Gb`), or when one of its collections is
  dropped or has its primary-key index rebuilt. Its reads then answer `409-17` until it's released. Writes are never
  affected.
- In a cluster a snapshot only exists on the node the client is connected to.

### `SERVER_INFO`
Says which build a client is talking to and whether the node is ready. It takes no fields, works before
authentication and keeps answering while the server shuts down, so a load balancer or health check can use it without
a password.
```json
{"type":"SERVER_INFO"}
```
How much comes back depends on who asks:

- Anyone gets `state`:
  ```json
  {"type":"SERVER_INFO","status":"OK","message":"Ok","server":{"state":"READY"}}
  ```
- An authenticated user also gets `version` (`dev` when not run from a packaged jar), `protocolVersion`,
  `storageFormatVersion` (see [docs/compatibility.md](compatibility.md)), `startedAtMs` and `uptimeMs`.
- An admin also gets `node`: `clusterEnabled`, `syncWrites` and `tlsEnabled`, plus `nodeId`, `aliveMembers` and
  `expectedSize` when clustering is on.
  ```json
  {"type":"SERVER_INFO","status":"OK","message":"Ok","server":{"state":"READY","version":"0.1.0",
    "protocolVersion":1,"storageFormatVersion":1,"startedAtMs":1762790400000,"uptimeMs":5231,
    "node":{"clusterEnabled":true,"nodeId":"n1","aliveMembers":3,"expectedSize":3,"syncWrites":true,"tlsEnabled":false}}}
  ```

| `state` | Meaning |
|---|---|
| `READY` | Serving requests |
| `SYNCING` | A cluster node that hasn't finished its first sync; admin changes answer `503-5` until it has |
| `SHUTTING_DOWN` | Stopping; every other request answers `503-13` |

`SERVER_INFO` always describes the node it was sent to and is allowed inside a transaction.

### `LIST_USERS` (admin only)
Returns the users and their permissions, never their password hashes. `aggregationSteps` is optional and works as in
`AGGREGATE`.
```json
{"type":"LIST_USERS"}
{
  "type": "LIST_USERS",
  "aggregationSteps": [
    {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"admin","value":true}},
    {"type":"SORT","fieldName":"_id","ascending":true}
  ]
}
```
Each user has `_id` (the username), `admin`, `globalPermissions`, `databasePermissions`, `collectionPermissions`,
`scriptPermissions` and `ownedDatabases`, so for example a `FILTER` on `ownedDatabases CONTAINS "mydb"` finds the
owners of a database. No match answers `404-5`.

## Permission model

| Permission | Grants |
|---|---|
| `admin` flag | Everything |
| Database ownership | Everything in that database, including dropping it |
| `globalPermissions` | `CREATE_DATABASE`, the right to create databases |
| `databasePermissions` | `READ` or `READ_WRITE` on every collection of a database |
| `collectionPermissions` | `READ` or `READ_WRITE` on one `database\|collection` |
| `scriptPermissions` | Per database: `NONE`, `RUN` or `MANAGE` |

Ownership wins over the data grants. A `collectionPermissions` entry replaces the database grant for that collection,
so it can widen it or narrow it: a database `READ_WRITE` with a `READ` entry for one collection leaves that collection
read-only. A collection without its own entry uses the database grant. `READ_WRITE` includes `READ`. There's no way to
deny reads on a single collection.

| Needs | Operations |
|---|---|
| `READ` | `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `GET_SCHEMA`, `LISTEN`, `LIST_PROCEDURES`, `LIST_TRIGGERS`, `LIST_SCHEDULES`, `START_SNAPSHOT` |
| `READ_WRITE` | `SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`, `REINDEX` |
| Admin or ownership | `DROP_DATABASE`, `SAVE_SCHEMA`, `DELETE_SCHEMA` |
| Any authenticated user | `LIST_DATABASES`, `GET_DATABASE_STATS` (both filtered to what the user can see), `START_TRANSACTION`, `COMMIT_TRANSACTION`, `ROLLBACK_TRANSACTION`, `SET_PASSWORD` for their own password |

A `JOIN` also needs `READ` on the joined collection, and a pipeline with a script operator also needs script `RUN`.
Inside a transaction every operation is checked on its own, so a `READ` user can open one but not write in it. Because
`CREATE_COLLECTION` needs `READ_WRITE` on the name it creates, a `READ` entry for a collection that doesn't exist yet
also stops the user from creating it.

`scriptPermissions` levels:

| Level | Allows |
|---|---|
| `NONE` (or missing) | Nothing |
| `RUN` | [`RUN_SCRIPT`](#run_script), [`CALL_PROCEDURE`](#call_procedure) and script operators in a pipeline |
| `MANAGE` | Everything `RUN` allows, plus [`SAVE_PROCEDURE`](#save_procedure), [`DELETE_PROCEDURE`](#delete_procedure), [`SAVE_TRIGGER`](#save_trigger), [`DELETE_TRIGGER`](#delete_trigger), [`TEST_TRIGGER`](#test_trigger), [`SAVE_SCHEDULE`](#save_schedule) and [`DELETE_SCHEDULE`](#delete_schedule). The trigger operations also need `READ_WRITE` on the collection |

Admins and owners have `MANAGE` on the databases they reach. `true` is read as `RUN` and `false` as `NONE`; any other
value is `400-1`.

Installing code is its own level, separate from `READ_WRITE`, because triggers and schedules run with their installer's
permissions, and a procedure runs with the permissions of whoever calls it, admins included. Starting a script doesn't
grant anything by itself either: every operation it sends is checked against the caller's data grants. Triggers and
schedules are the exception, they run as their definer.

## Fault injection (testing and debugging only)

These three operations are not for applications. They let the test suites stop a node at an exact point inside the
engine (pause there, crash there, drop one reply to a peer, or fail one `fsync`) to reproduce failures that only happen
between two steps. They only work when the server was started with `faultInjectionEnabled=true`, and answer `403-3`
otherwise. All three are admin only, act on the node you're connected to and are refused inside a transaction.

A fault is armed at a named point and fires once, the first time a thread reaches that point with a matching subject.
The subject is `<db>|<collection>` except where the table says otherwise.

| Point | Fires when |
|---|---|
| `WRITE_OWNERSHIP_RECHECKED` | A clustered write confirmed this node owns the collection and hasn't applied yet |
| `WRITE_OWNER_PROBED` | Same place as `WRITE_OWNERSHIP_RECHECKED`, kept separate to find a collection's owner without releasing a parked write |
| `DELETE_TOMBSTONE_RESERVED` | A clustered delete wrote its tombstone and hasn't deleted the document yet |
| `ANTI_ENTROPY_SNAPSHOTTED` | An anti-entropy round took its snapshot and hasn't sent its digest yet |
| `REPLICATED_APPLY_LOCKED` | A replica locked the collection for a replicated write and hasn't applied it yet |
| `REPLICATED_TOMBSTONES_APPENDED` | A replica wrote a replicated delete batch's tombstones and hasn't deleted the documents yet |
| `PK_INDEX_REPLACED` | A `REINDEX` with `primaryKey` wrote the new primary-key index and hasn't rebuilt the rest yet |
| `INDEX_BATCH_FLUSHED` | A background index batch wrote the first of several index logs and not the others |
| `PAGE_COPY_APPENDED` | An update logged its record and hasn't written the pages yet |
| `PK_ROW_COMMITTED` | An update or delete wrote everything its logged record describes |
| `WAL_REWRITE_BEGUN` | A page rewrite logged its start and hasn't rewritten the page yet |
| `PAGE_VACUUM_RENAMED` | A page rewrite moved the new page into place and hasn't recorded the moved documents yet |
| `WAL_APPENDED` | A write appended its record to the write-ahead log and hasn't written the pages yet |
| `WAL_CHECKPOINT_FORCED` | A checkpoint synced the pages and hasn't rewritten the log yet |
| `WAL_GROUP_APPENDED` | A group of writes appended its records and hasn't synced them yet |
| `WAL_GROUP_FORCED` | A group of writes synced its records and hasn't finished applying them |
| `GROUP_RUNS_STAGED` | A group of writes recorded its trigger runs and hasn't written anything else yet |
| `PEER_REPLY` | A node applied a peer's request and hasn't replied. Subject: the request type, like `COMMIT_TX` |
| `FILE_FORCED` | A file or folder is about to be synced. Subject: the file name, like `orders.wal`, or a folder name ending in `/` |

| Action | Effect |
|---|---|
| `PAUSE` | Parks the thread until `RELEASE_FAULT`, for at most 120 seconds |
| `HALT` | Kills the process at that point with exit status `137`, like `kill -9` |
| `DROP` | Skips the reply (`PEER_REPLY` only) |
| `FAIL` | Makes the sync fail like a failed `fsync` (`FILE_FORCED` only) |

### `ARM_FAULT` (admin only, testing only)
`match` is optional, and without it any subject fires. Arming an armed point releases the previous arm first.
```json
{"type":"ARM_FAULT","point":"PEER_REPLY","action":"DROP","match":"COMMIT_TX"}
{"type":"ARM_FAULT","point":"FILE_FORCED","action":"FAIL","match":"orders.wal"}
```

### `AWAIT_FAULT` (admin only, testing only)
Waits until the armed point has fired, up to `timeoutMs` (1-120000). Answers `408-3` if it didn't, or if the point isn't
armed.
```json
{"type":"AWAIT_FAULT","point":"WRITE_OWNERSHIP_RECHECKED","timeoutMs":30000}
```

### `RELEASE_FAULT` (admin only, testing only)
Lets a paused thread go and disarms the point. Without `point` it does that for every point.
```json
{"type":"RELEASE_FAULT","point":"WRITE_OWNERSHIP_RECHECKED"}
```

Errors: `403-3` fault injection is off, `403-1` not an admin, `400-1` an unknown point or action, `DROP` or `FAIL` on the
wrong point, or a `timeoutMs` out of range, `408-3` the point wasn't reached in time.

## Error codes

Every error reply has an `errorCode`. The `message` may add details, like the offending id or field, to the text below.
Retryable codes are the ones a client may resend on its own (see [Retrying](#retrying)).

| Code | `status` | Retryable | Meaning |
|---|---|---|---|
| `400-1` | `ERROR` |  | The request failed validation; the message says why |
| `400-2` | `ERROR` |  | Entry size exceeds `maxEntrySize` |
| `400-3` | `ERROR` |  | Duplicate `_id` in a `BULK_SAVE` |
| `400-4` | `ERROR` |  | Cannot delete the last admin user |
| `400-5` | `ERROR` |  | Cannot demote the last admin user |
| `400-6` | `ERROR` |  | Current password is incorrect |
| `400-7` | `ERROR` |  | Document doesn't comply with the collection schema |
| `400-8` | `ERROR` |  | The JSON schema isn't valid or uses an unsupported keyword |
| `400-9` | `ERROR` |  | Script failed: it threw or didn't parse |
| `400-10` | `ERROR` |  | Script source is too big |
| `400-11` | `ERROR` |  | Script exceeded its instruction or depth budget |
| `400-12` | `ERROR` |  | Script exceeded its memory budget |
| `400-13` | `ERROR` |  | The procedure source doesn't parse |
| `400-14` | `ERROR` |  | The trigger definition isn't valid, or a trigger still uses the procedure |
| `400-15` | `ERROR` |  | Script result is too big |
| `400-16` | `ERROR` |  | The schedule definition isn't valid, or a schedule still uses the procedure |
| `400-17` | `ERROR` |  | The database has the maximum number of schedules |
| `400-18` | `ERROR` |  | A procedure imports a `procedures/<name>` that doesn't exist or is disabled |
| `400-19` | `ERROR` |  | Script operators aren't allowed in a `LISTEN` |
| `400-20` | `ERROR` |  | The script's result promise never settled |
| `400-21` | `ERROR` |  | A before trigger refused the write |
| `400-22` | `ERROR` |  | The request line is too big; sent as a `CLOSE_CONNECTION` frame, then the connection is closed |
| `400-23` | `ERROR` |  | Schema validation ran out of its step budget |
| `400-24` | `ERROR` |  | The schema is too complex once its `$ref`s are followed |
| `400-25` | `ERROR` |  | Too many listens on the connection or the server |
| `400-26` | `ERROR` |  | The transaction would pass `transactionMaxOps` or `transactionMaxBytes`; the transaction stays usable |
| `400-27` | `ERROR` |  | The filter or `MAP` has more operands than `maxFilterOperands` |
| `400-28` | `ERROR` |  | A `MAP` value or a `JOIN` array exceeds `maxEntrySize` |
| `400-29` | `ERROR` |  | The schedule timeout exceeds `scheduleMaxTimeoutMs` |
| `400-30` | `ERROR` |  | A script array exceeds `scriptMaxArrayLength` |
| `400-31` | `ERROR` |  | A schema regular expression exceeds `schemaMaxPatternLength` |
| `400-32` | `ERROR` |  | The collection has the maximum number of triggers |
| `400-33` | `ERROR` |  | The user has the maximum number of schedules |
| `400-34` | `ERROR` |  | The database has the maximum number of procedures |
| `400-35` | `ERROR` |  | The schedule fires more often than `scheduleMinIntervalMs` allows |
| `400-36` | `ERROR` |  | The aggregation built more than `aggregateMaxResultBytes` |
| `400-37` | `ERROR` |  | The `LISTEN` request exceeds `listenMaxRequestBytes` |
| `400-38` | `ERROR` |  | The user already has `maxListensPerUser` listens |
| `400-39` | `ERROR` |  | The schema has more than `schemaMaxPatterns` regular expressions |
| `400-40` | `ERROR` |  | The database has the maximum number of collections |
| `400-41` | `ERROR` |  | The server has the maximum number of databases |
| `400-42` | `ERROR` |  | The collection has the maximum number of indexes |
| `400-43` | `ERROR` |  | The command isn't valid (not JSON, unknown `type`, too dense, too deep, a bad value or field); the connection stays open |
| `400-44` | `ERROR` |  | The delete condition matches more than `deleteMaxMatches` documents; nothing was deleted |
| `400-45` | `ERROR` |  | The update condition matches more than `updateMaxMatches` documents; nothing was written |
| `401-1` | `UNAUTHENTICATED` |  | Must authenticate first |
| `401-2` | `UNAUTHENTICATED` |  | User no longer exists |
| `401-3` | `ERROR` |  | Wrong username or password |
| `401-4` | `UNAUTHENTICATED` |  | Too many failed logins on this connection; the connection is closed |
| `403-1` | `FORBIDDEN` |  | No permission |
| `403-2` | `FORBIDDEN` |  | Scripts (or schedules) are disabled on this server |
| `403-3` | `FORBIDDEN` |  | Fault injection is disabled on this server |
| `403-4` | `FORBIDDEN` |  | The procedure is used by another user's trigger, before hook or schedule |
| `403-5` | `FORBIDDEN` |  | The trigger or schedule belongs to another user |
| `403-6` | `FORBIDDEN` |  | `includeSource` or `includeArgs` needs script `MANAGE` |
| `404-1` | `NOT_FOUND` |  | User not found |
| `404-2` | `NOT_FOUND` |  | Entry not found |
| `404-3` | `NOT_FOUND` |  | No results |
| `404-4` | `NOT_FOUND` |  | Database not found |
| `404-5` | `NOT_FOUND` |  | No users found |
| `404-6` | `NOT_FOUND` |  | No index on that field |
| `404-7` | `NOT_FOUND` |  | Listen not found |
| `404-8` | `NOT_FOUND` |  | Procedure not found or disabled |
| `404-9` | `NOT_FOUND` |  | Trigger not found |
| `404-11` | `NOT_FOUND` |  | Collection not found (a clustered node answers `503-10` instead) |
| `404-12` | `NOT_FOUND` |  | The collection has no schema |
| `404-13` | `NOT_FOUND` |  | Snapshot not found: released, expired or another connection's |
| `408-1` | `ERROR` |  | Script exceeded its time budget |
| `408-2` | `ERROR` |  | Script was cancelled |
| `408-3` | `ERROR` |  | The armed fault point wasn't reached in time |
| `408-4` | `ERROR` |  | Didn't authenticate within `authTimeoutMs`; sent as a `CLOSE_CONNECTION` frame, then the connection is closed |
| `408-5` | `ERROR` |  | The transaction was rolled back after `transactionMaxAgeMs`; answered once, to the next request |
| `408-6` | `ERROR` |  | The aggregation exceeded `aggregateTimeoutMs` |
| `409-1` | `ERROR` |  | User already exists |
| `409-2` | `ERROR` |  | Database already exists |
| `409-3` | `ERROR` |  | A transaction is already open on this connection |
| `409-4` | `ERROR` |  | No transaction is open on this connection |
| `409-5` | `ERROR` |  | Couldn't lock the collection in time; the transaction is aborted |
| `409-6` | `ERROR` |  | Not allowed while a transaction is open |
| `409-7` | `ERROR` |  | Transaction aborted: a participant couldn't prepare |
| `409-8` | `ERROR` |  | The procedure, trigger or schedule was changed by someone else (`ifVersion`) |
| `409-9` | `ERROR` |  | The transaction was aborted and must be rolled back |
| `409-10` | `ERROR` |  | The commit was decided but part of it didn't apply; the server finishes it. Never roll back or resend its writes |
| `409-11` | `ERROR` |  | A name that differs only by case already exists |
| `409-12` | `ERROR` |  | A previous transaction on this connection is still being resolved; retry once it finishes |
| `409-13` | `ERROR` |  | A participant lost this transaction's writes; roll back and start over |
| `409-14` | `ERROR` |  | The transaction already committed on its owner and can't be rolled back |
| `409-15` | `ERROR` |  | The primary-key index can't be rebuilt from the page files |
| `409-16` | `ERROR` |  | The transaction was rolled back after `transactionIdleTimeoutMs`; answered once, to the next request |
| `409-17` | `ERROR` |  | The snapshot was aborted; the message says why |
| `409-18` | `ERROR` |  | A document with this `_id` already exists (`failOnUpdate`) |
| `410-1` | `NOT_FOUND` |  | The listen ended; pushed once on its connection |
| `413-1` | `ERROR` |  | Too many unread responses; sent as a `CLOSE_CONNECTION` frame, then the connection is closed |
| `421-1` | `ERROR` |  | This node doesn't own the collection |
| `421-3` | `ERROR` |  | This read joins collections the transaction wrote on different nodes |
| `500-1` | `ERROR` |  | Error during authentication |
| `500-2` | `ERROR` |  | Error creating user |
| `500-3` | `ERROR` |  | Error deleting user |
| `500-4` | `ERROR` |  | Error changing password |
| `500-5` | `ERROR` |  | Error changing permissions |
| `500-6` | `ERROR` |  | Error saving entries |
| `500-7` | `ERROR` |  | Error saving entry |
| `500-8` | `ERROR` |  | Error retrieving entry |
| `500-9` | `ERROR` |  | Error processing aggregation |
| `500-10` | `ERROR` |  | Error deleting entry |
| `500-11` | `ERROR` |  | Error creating database |
| `500-12` | `ERROR` |  | Error updating database owners |
| `500-13` | `ERROR` |  | Error dropping database |
| `500-14` | `ERROR` |  | Error listing databases |
| `500-15` | `ERROR` |  | Error creating collection |
| `500-16` | `ERROR` |  | Error dropping collection |
| `500-17` | `ERROR` |  | Error listing collections |
| `500-18` | `ERROR` |  | Error creating index |
| `500-19` | `ERROR` |  | Error listing users |
| `500-20` | `ERROR` |  | Error dropping index |
| `500-21` | `ERROR` |  | Error reindexing |
| `500-22` | `ERROR` |  | Error gathering database stats |
| `500-23` | `ERROR` |  | Error processing a listen |
| `500-24` | `ERROR` |  | Error processing a transaction operation |
| `500-25` | `ERROR` |  | Error saving the schema |
| `500-26` | `ERROR` |  | Error deleting the schema |
| `500-27` | `ERROR` |  | Error saving the procedure |
| `500-28` | `ERROR` |  | Error deleting the procedure |
| `500-29` | `ERROR` |  | Error saving the trigger |
| `500-30` | `ERROR` |  | Error deleting the trigger |
| `500-31` | `ERROR` |  | Error saving the schedule |
| `500-32` | `ERROR` |  | Error deleting the schedule |
| `500-33` | `ERROR` |  | A committed transaction couldn't be fully applied. It keeps its locks and the server keeps retrying it; other readers of its collections get `503-18` |
| `500-34` | `ERROR` |  | The write was logged but couldn't be applied. It will be, so it may already count: don't resend it blindly |
| `500-35` | `ERROR` |  | Error gathering server info |
| `500-36` | `ERROR` |  | Error opening the snapshot |
| `500-37` | `ERROR` |  | Error reading the snapshot |
| `503-1` | `ERROR` |  | Too many connections; sent as a `CLOSE_CONNECTION` frame, then the connection is closed (with TLS, closed with no frame) |
| `503-2` | `ERROR` | yes | The cluster has no write quorum |
| `503-3` | `ERROR` |  | Timed out waiting for the replication quorum. The write did happen, so don't resend it blindly |
| `503-4` | `ERROR` | yes | The collection's owner is unreachable |
| `503-5` | `ERROR` | yes | The admin coordinator is synchronizing |
| `503-6` | `ERROR` |  | Too many scripts running; the message names the limit (`node`, `user` or `database`). Nothing ran |
| `503-7` | `ERROR` |  | The node the script was placed on never reported an outcome, so it may have run |
| `503-8` | `ERROR` |  | The collection's owner never reported an outcome, so the write may have been applied |
| `503-9` | `ERROR` | yes | The admin coordinator couldn't be found |
| `503-10` | `ERROR` | yes | The collection hasn't reached this node yet |
| `503-11` | `ERROR` |  | The collection's schema can't be read, so the write can't be validated |
| `503-13` | `ERROR` |  | The server is shutting down; try another node or later. A write refused with it was not applied |
| `503-14` | `ERROR` |  | Too many unauthenticated connections from this address; sent as a `CLOSE_CONNECTION` frame, then the connection is closed (with TLS, closed with no frame) |
| `503-15` | `ERROR` |  | Listens already keep `listenMaxRetainedBytes` of requests; retry once some end |
| `503-16` | `ERROR` | yes | The background index queue is full; nothing was written |
| `503-17` | `ERROR` | yes | Too many bytes of unsent responses on the server; only reads get it |
| `503-18` | `ERROR` | yes | The collection is held by a committed transaction that is still being applied |
| `503-19` | `ERROR` | yes | The collection has logged writes that can't be applied yet (often a full disk) |
| `503-20` | `ERROR` | yes | Running aggregations hold `aggregateTotalHeldBytes` |
| `503-21` | `ERROR` | yes | Open transactions hold `transactionTotalMaxBytes`; the write wasn't buffered |
| `503-22` | `ERROR` | yes | The node has `snapshotMaxOpen` snapshots open |
