# LWNRDB wire protocol

Everything a client and the server say to each other: how a connection is opened and authenticated, what every request
and reply looks like, how replies are matched to requests, which errors are safe to retry, and then every operation,
permission and error code in turn.

- [Who this is for](#who-this-is-for)
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
- [Fault injection](#fault-injection-testing-and-debugging-only)
- [Error codes](#error-codes)

## Who this is for

Anyone writing, porting or debugging a client, and anyone talking to the server by hand. If you only want to use the
database from an application, take one of the [client libraries](../clients/README.md), which implement everything
below. The machine-readable side of this page is [`clients/protocol`](../clients/protocol/README.md): the operation,
error-code and custom-type catalogues generated from the server, and the request and response fixtures every official
client is tested against.

## Transport

A client opens a TCP connection to the server's `port` (default `8989`), plain or, with `tlsEnabled=true`, TLS (see
[TLS / secure connections](../README.md#tls--secure-connections)); a TLS server refuses plaintext at the handshake. Both
directions carry UTF-8 text, one JSON object per line, each line ended by `\n`. Nothing else is framed: there is no
length prefix, no request id and no handshake beyond `AUTHENTICATE`.

### Connection limits

Each request is one line terminated by `\n`; a single `\r` right before it is stripped. A lone `\r` does **not** end a line (it used to): it is kept as part of the request.

- **Line size.** A line may be at most `maxRequestSize` bytes (default `64Mb`) once the connection has authenticated, and at most **64Kb** before that (only `AUTHENTICATE` and `CLOSE_CONNECTION` are served unauthenticated, and each is a few hundred bytes; any other request is answered `401-1` without being validated). The server refuses as soon as the unterminated bytes pass the limit, without waiting for the newline: it answers one `CLOSE_CONNECTION` frame with `400-22` and closes the connection, because the rest of an oversized line cannot be skipped safely.
- **Token budget.** A line is also refused if it is denser than one JSON token per 4 bytes of the line limit (a 64Kb unauthenticated line may hold 16 384 tokens, a 64Mb one 16 777 216). Real documents, vectors and pipelines average well above 4 bytes per token; a huge flat array such as `[1,1,1,...]` does not. Such a line is answered `400-43` and the connection stays open.
- **Authentication deadline.** A connection must authenticate within `authTimeoutMs` (default `10000`) of being accepted. The deadline is absolute: traffic, blank lines and failed `AUTHENTICATE` attempts do not extend it. When it expires the server answers one `CLOSE_CONNECTION` frame with `408-4` and closes the connection. With `tlsEnabled=true` the TLS handshake runs under the same deadline: a client that stalls or drips its handshake is closed at `authTimeoutMs` plus one second. A TLS connection over `maxConnections` or `maxUnauthenticatedConnectionsPerAddress` is closed before its handshake, with no frame (the refusal could only be sent after the handshake it would cost). Once authenticated, a connection is never closed for being idle.
- **Unauthenticated connections per address.** One remote address may hold at most `maxUnauthenticatedConnectionsPerAddress` (default `16`) connections that have not authenticated yet; the next one is answered `503-14` and closed. Authenticating frees the slot. Clients behind one NAT address share the allowance.
- **Invalid requests before authentication.** A connection that sends `maxUnauthenticatedInvalidRequests` (default `5`) requests before it authenticates that are not a successful `AUTHENTICATE` (unparseable lines, requests answered `401-1`, failed attempts) is closed after the last answer.
- **Slow readers.** Every response and LISTEN update goes through a per-connection outbound queue, so a client that stops reading never blocks a LISTEN worker or another connection. A response of at most 16Kb to a connection that sends one request at a time is written by the connection's own thread; anything larger, every LISTEN update, and every response on a connection that has ever sent a request before the previous one was answered (pipelining) is written by a separate writer thread, so such a connection keeps reading and its open transaction is still rolled back after `transactionIdleTimeoutMs`. A response write that moves less than 64Kb in `clientWriteStallTimeoutMs` (default `30000`) closes the connection, which rolls back its open transaction, so a client that reads a large response a few kilobytes at a time is closed like one that stopped reading. Once the unread backlog passes `clientOutboundQueueMaxBytes` (default `16Mb`, counted in UTF-8 bytes as written to the socket) the backlog is dropped, the connection's listens end, one `CLOSE_CONNECTION` frame with `413-1` is queued and the connection is closed. A single response larger than the cap is still delivered when nothing is waiting behind it. Across all connections the encoded responses waiting to be written are bounded by `maxUnsentResponseBytes` (default a quarter of the heap): a read whose response of 64Kb or more would pass it is answered `503-17` instead, and retrying it is safe.
- **Idle transactions.** A connection holding an open transaction must send its next request within `transactionIdleTimeoutMs` (default `30000`), and a transaction is rolled back once it is older than `transactionMaxAgeMs` (default `600000`) however busy it is; see [Transactions](#transactions).
- `maxConnections` counts client sockets only; forwarded cluster requests and transaction sessions no longer take a slot.

```jsonl
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The request exceeds the maximum allowed size","errorCode":"400-22"}
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The connection did not authenticate in time","errorCode":"408-4"}
```

## Session lifecycle

1. **Connect.** The connection counts against `maxConnections`, and against `maxUnauthenticatedConnectionsPerAddress`
   until it authenticates.
2. **Authenticate** with [`AUTHENTICATE`](#authenticate) within `authTimeoutMs`. Before that only `AUTHENTICATE`,
   `CLOSE_CONNECTION` and [`SERVER_INFO`](#server_info) are answered; anything else is answered `401-1`.
3. **Send requests**, one per line, as many as you like before reading the replies.
4. **Close** with [`CLOSE_CONNECTION`](#close_connection), which the server answers with
   `{"type":"CLOSE_CONNECTION","status":"OK","message":"Bye!","errorCode":null}` before it closes the socket, or simply
   close the socket.

Everything a session holds belongs to its connection and ends with it: the authenticated user, the one open
transaction (rolled back when the connection closes) and every `LISTEN` registration. A connection cannot hand any of
them to another.

## Request envelope

A request is one JSON object with a `type` naming the operation (the names are in
[`operations.json`](../clients/protocol/operations.json)). Operations on a database carry `databaseName`, operations on a
collection carry `databaseName` and `collectionName`, and each operation adds its own fields, listed under
[Operations](#operations).

```json
{"type":"FIND_BY_ID","databaseName":"shop","collectionName":"orders","_id":"o-1"}
```

- A field the operation does not know is ignored.
- A request may nest objects and arrays at most 128 levels deep, counted from the request's own root object. A deeper
  request is refused as an invalid command, and a script's `db.save`/`db.bulkSave` refuses a document nested more than
  128 levels. Documents stored before this limit existed stay readable.
- A line that is not a JSON object, names no known `type` or exceeds the token budget (see
  [Connection limits](#connection-limits)) is answered `400-43`, with `"type": null` when the server never read the
  request's `type`, and the connection stays open.

## Response envelope

Every reply is one JSON object carrying `type` (the operation it answers), `status`, `message` and `errorCode`, plus
the operation's own fields:

```json
{"_id":"o-1","inserted":true,"type":"SAVE","status":"OK","message":"Successfully saved","errorCode":null}
{"type":"FIND_BY_ID","status":"NOT_FOUND","message":"Entry not found","errorCode":"404-2"}
```

| `status` | Meaning |
|---|---|
| `OK` | The request was carried out; `errorCode` is `null` |
| `ERROR` | It was refused or failed |
| `NOT_FOUND` | What it names does not exist |
| `UNAUTHENTICATED` | The connection is not authenticated, or its user no longer exists |
| `FORBIDDEN` | The user lacks the permission it needs |

- `errorCode` is `null` on success and `NNN-N` otherwise: a three-digit HTTP-style range and a sequential number
  (`401-1`). The 4xx range covers client errors, 5xx server errors, and 503 means the server is temporarily unable to
  serve the request. A client should branch on `errorCode`, never on `message`, whose text may carry details (an id, a
  field name) and may change. Every code is listed under [Error codes](#error-codes).
- The server writes every field of a reply, `null` ones included, so a field's absence never means `null`. The
  examples on this page often leave `errorCode` and other `null` fields out.
- Fields are not in a fixed order; read them by name.
- **Some empty results are errors on the wire.** `FIND_BY_ID` answers `404-2` for an id that does not exist,
  `AGGREGATE` answers `404-3` when the pipeline matches nothing and `LIST_USERS` answers `404-5` when no user
  matches. A client turns exactly those three codes into an empty result; every other `NOT_FOUND` is a real error.

## Matching replies to requests

The server answers the requests of one connection **in the order it received them**, one reply each, so a client keeps
a first-in, first-out list of requests it is waiting on and hands each reply to the oldest. A client may pipeline:
send several requests before reading any reply. Two kinds of frame are not replies and must be taken out before the
list is consulted:

- **`LISTEN` pushes.** A frame whose `type` is `LISTEN` and that carries `"isUpdate": true` (new results) or
  `"errorCode": "410-1"` (the server ended the listen) belongs to the registration named by its `listenId`, not to a
  waiting request. Pushes are written by background workers, so one can arrive between any two replies, before or
  after the reply to the request that caused it: a `DROP_COLLECTION` that ends a listen on the same connection may be
  answered before or after that listen's `410-1` frame. The reply to `LISTEN` itself carries
  `"isUpdate": false`, and a refused `LISTEN` has no `listenId`, so both are ordinary replies.
- **An unrequested `CLOSE_CONNECTION`.** When the server closes a connection on its own (an oversized line `400-22`,
  the authentication deadline `408-4`, too many connections from one address `503-14`, an unread backlog `413-1`) it
  writes one `CLOSE_CONNECTION` frame carrying the reason and closes the socket. Every request still waiting has
  failed; whether it was carried out is unknown. The server also closes a connection right after an ordinary reply in
  two cases, with no extra frame: an `AUTHENTICATE` answered `401-4`, and the last of
  `maxUnauthenticatedInvalidRequests` refused requests before authentication.

Two more rules keep the list aligned:

- **A reply line that is not JSON fails only the oldest waiting request.** The server never sends one today, since
  even an unparseable request is answered with a `400-43` object, but a client should survive one and keep the
  connection.
- **A request that times out on the client must close its connection.** The server will still answer it, and a late
  reply would otherwise be handed to the next request.

## Values

Documents and request fields are plain JSON, with these rules:

- **Numbers** are IEEE-754 doubles. An integer above 2^53 loses precision; send it as a string. A number outside the
  double range (`1e400`) is refused with `400-43`, and `Infinity` and `NaN` are not JSON.
- **`_id`** is a string of 1–64 letters, digits, `_` and `-`, compared exactly (case-sensitive) everywhere; every other
  string comparison is case-insensitive. A `SAVE` without one gets a UUID, returned in the reply's `_id`. See
  [Naming rules](#naming-rules).
- **Custom values** travel as strings matching `^#[a-zA-Z0-9]{3,20}\(.*\)$` (the `detectionRegex` of
  [`custom-types.json`](../clients/protocol/custom-types.json)):

  | Text | Value |
  |---|---|
  | `#datetime(2024-01-01T10:00)` | An ISO-8601 local date-time, seconds and fractions optional, **without** a zone or offset |
  | `#time(09:30)` | An ISO-8601 local time |
  | `#geo(40.71,-74.0)` | A point: latitude −90..90, longitude −180..180 |
  | `#vector(0.12,0.44,0.91)` | A dense vector of numbers |

  The server parses such a string wherever a document or an operand holds one, and writes the value back in the same
  form. Equal values compare equal whatever their spelling (`#datetime(2024-01-01T10:00)` equals
  `#datetime(2024-01-01T10:00:00)`, `#geo(1,2)` equals `#geo(1.0,2.0)`). A consequence: **a plain string that matches
  the pattern cannot be stored as text**, so a client should refuse one rather than let it change meaning. A client
  holding a zoned date-time must convert it to the server's local form itself.

## Retrying

A client may resend a request on its own **only when the error's code is in the retryable set**, the codes marked
*yes* in the `Retryable` column of [Error codes](#error-codes) (and `"retryable": true` in
[`error-codes.json`](../clients/protocol/error-codes.json)). Each of them means nothing was applied. Back off between
attempts; most of them clear in milliseconds to seconds. A few other rows say when repeating a request is safe (a
script refused with `503-6` never ran, for example); resending those is the caller's decision, not the client's.

Never retry automatically:

- `503-3`: the write committed, only its replication fell short of a quorum. Sending it again writes it again, which is
  harmless for a `SAVE` with an `_id` or a `DELETE` and wrong for a `SAVE` without one or a read-modify-write script.
- `503-7` and `503-8`: the outcome is unknown, so only an idempotent request may be sent again, and that is the
  caller's decision.
- `500-33`, `500-34` and `409-10`: the write or transaction was durably logged or decided, and the server finishes it.
- Anything inside a transaction: see [Transactions on the wire](#transactions-on-the-wire).

After a lost connection, resend only requests that change nothing: `FIND_BY_ID`, `AGGREGATE`, the `LIST_*` and `GET_*`
operations and `SERVER_INFO`. Any write may have been applied before the connection dropped, and a resent `SAVE`
without an `_id` stores a second document.

## Transactions on the wire

A transaction lives on the connection that sent `START_TRANSACTION`, so a client must send every request of it, and
the `COMMIT_TRANSACTION` or `ROLLBACK_TRANSACTION`, on that one connection, and send nothing else on it meanwhile. A
connection pool hands that connection to the transaction for its whole life. The rules a client needs, all detailed
under [Transactions](#transactions):

- While a transaction is open, the connection accepts only the data operations, `COMMIT_TRANSACTION`,
  `ROLLBACK_TRANSACTION`, `SERVER_INFO` and `CLOSE_CONNECTION`; anything else answers `409-6`.
- After an idle (`transactionIdleTimeoutMs`) or overage (`transactionMaxAgeMs`) rollback, the connection's next request
  is answered `409-16` or `408-5` once and not executed.
- After `409-5` the transaction is aborted: everything but `ROLLBACK_TRANSACTION` answers `409-9`.
- A `COMMIT_TRANSACTION` answered `500-33` or `409-10` was decided and the server finishes applying it; the
  transaction stays registered on the connection, a `ROLLBACK_TRANSACTION` answers `500-33` rather than undoing it, and
  in a cluster a re-sent `COMMIT_TRANSACTION` on the same connection finishes a slice the owner still holds (see
  [clustering.md](clustering.md)). Never send its writes again.
- A `COMMIT_TRANSACTION` whose reply never came has an unknown outcome: close that connection without a rollback and
  read the data to find out.
- Closing the connection rolls back an open transaction.

## Versioning

`SERVER_INFO` tells any authenticated user the server's `protocolVersion`, which is raised by every change to the wire:
a request or reply field, an operation, an error code. It is `1` today. A client speaks one `protocolVersion` and can
compare it after authenticating. What may change between releases, and how releases are numbered, is in
[compatibility.md](compatibility.md).

## Writing a client: checklist

- One JSON object per line, UTF-8, `\n`-terminated, at most `maxRequestSize` bytes (64Kb before authenticating).
- Authenticate first and within `authTimeoutMs`; authentication belongs to the connection.
- Match replies first-in, first-out; take `LISTEN` pushes (`isUpdate: true` or `410-1`) and unrequested
  `CLOSE_CONNECTION` frames out first.
- Close a connection whose request timed out; fail only the oldest request on a non-JSON reply line.
- Branch on `errorCode`, never on `message`; read `404-2`, `404-3` and `404-5` as empty results.
- Retry only the retryable codes, never inside a transaction, and after a lost connection only reads.
- Pin a transaction to one connection, and keep `LISTEN` registrations off pooled connections, since a registration
  ends with its connection.
- Write custom values in their `#type(...)` form and refuse plain strings that match it.
- Test against [`clients/protocol`](../clients/protocol/README.md): produce every request fixture from your public API
  and decode every response fixture.

## Naming rules

- **Database / collection names**: 3–64 characters, alphanumeric + `_` and `-`. The names `admin` and
  `admin_pages` are reserved, and the collection name `script_runs` is reserved; all three are matched
  case-insensitively, so `Admin` is refused too.
- **IDs (`_id`)**: 1–64 characters, alphanumeric + `_` and `-`.
- **Index field names**: 1–64 characters from `A-Z a-z 0-9 _ . -`. A dot addresses a nested path
  (`address.city`). A field whose name falls outside this set cannot be indexed, but stays fully
  queryable — `FILTER` answers it with a collection scan, as it does for any unindexed field.
- **Names must differ by more than case.** Every name above becomes a path segment or a file name on
  disk, and a case-insensitive filesystem (macOS APFS, Windows NTFS) would give two such names one
  file. `CREATE_DATABASE`, `CREATE_COLLECTION`, `CREATE_INDEX`, `SAVE_PROCEDURE` and `SAVE_SCHEDULE`
  therefore answer `409-11` when a sibling name differs from the new one only by case. The refusal is
  unconditional, including on a case-sensitive filesystem, so a data directory stays portable and
  every node of a cluster agrees on which name is legal.

> **Upgrade note.** Three refusals are new. A `CREATE_*` whose name differs from an existing sibling
> only by case now answers `409-11` where it used to succeed; a `CREATE_INDEX` on a field name
> outside `A-Z a-z 0-9 _ . -` now answers `400-1`; and `admin`, `admin_pages` and `script_runs` are
> matched case-insensitively, so `Admin` is refused as reserved rather than resolving to the internal
> `admin` database. If you already run on a case-insensitive filesystem, look for databases,
> collections, indexed fields or procedures whose names differ only by case before upgrading: they
> already share one set of files, and the server names them at startup with a
> *"share one on-disk path"* warning.
>
> Separately, a field index written by an earlier version that holds a string containing an unpaired
> surrogate stores that character as `?`, which collapses distinct values onto one key. Run
> [`REINDEX`](#reindex) once on any such collection after upgrading; the rebuild now writes those
> values escaped.

## Operations

### `LIST_DATABASES`
Requires authentication (an unauthenticated connection gets `401-1`). An admin sees every database; any other
user sees only the databases they own or hold a grant on: a `databasePermissions` entry, a `collectionPermissions`
entry for one of its collections (`database|collection`), or a `scriptPermissions` level other than `NONE`. A
database the caller cannot reach is simply left out of the list.
```json
{"type":"LIST_DATABASES"}
```

### `CREATE_DATABASE`
```json
{"type":"CREATE_DATABASE","databaseName":"my_db"}
```
Refused with `400-41` once the server already holds `maxDatabases` user databases.

### `DROP_DATABASE`
```json
{"type":"DROP_DATABASE","databaseName":"my_db"}
```

### `LIST_COLLECTIONS`
```json
{"type":"LIST_COLLECTIONS","databaseName":"my_db"}
```

### `CREATE_COLLECTION`
```json
{"type":"CREATE_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```
Refused with `400-40` once the database already holds `maxCollectionsPerDatabase` collections; re-creating an existing collection is always allowed.

### `DROP_COLLECTION`
```json
{"type":"DROP_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```

### `SAVE` (insert or update)
`_id` is optional. If provided and the document exists it is updated; if it does not exist it is created with that id. If `_id` is omitted a UUID is auto-assigned.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"name":"Alice"}}
```
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","object":{"_id":"user-1","name":"Alice"}}
```
The id may also be sent beside the object instead of inside it; the two forms address the same document. When both are present the object's `_id` wins.
```json
{"type":"SAVE","databaseName":"my_db","collectionName":"my_coll","_id":"user-1","object":{"name":"Alice"}}
```
Every number is stored as an IEEE-754 double, and a request carrying one outside that range —
`1e400`, `-1e400`, or a digit string that overflows — is refused as an unparseable command rather
than stored. Earlier versions answered `OK` and wrote `null` in its place, so the cached document and
the stored one disagreed until the next restart. `Infinity` and `NaN` were already refused as invalid
JSON.

### `BULK_SAVE`
At least one object required.
```json
{"type":"BULK_SAVE","databaseName":"my_db","collectionName":"my_coll","objects":[{"name":"Alice"},{"_id":"user-2","name":"Bob"}]}
```

### `FIND_BY_ID`
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```
Read operations accept an optional `"dirtyRead": true` (default `false`); see [Concurrency & locking](../README.md#concurrency--locking).
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1","dirtyRead":true}
```

### `DELETE`
```json
{"type":"DELETE","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```

### `AGGREGATE`
Queries run through a pipeline of steps. `aggregationSteps` may be empty (returns all documents). Accepts an optional top-level `"dirtyRead": true` (default `false`); see [Concurrency & locking](../README.md#concurrency--locking).
Also accepts an optional top-level `"analyze": true` (default `false`); see [Explain / Analyze](#explain--analyze).
A pipeline is stopped with `408-6` once it has run for `aggregateTimeoutMs` (default `300000`, `0` = no limit). An `IN`/`NOT_IN` list or a `within` polygon may hold at most `maxFilterOperands` (default `10000`) operands, refused with `400-27` past it; the same limit caps the operands of one conjunction (`AND`/`OR`/…), the total operator nodes of one `FILTER` step, and the operators plus mid-operator operands of one `MAP` step. A `MAP` `fieldName` may nest at most 128 levels. One output row of a `JOIN` may carry a joined array of at most `maxEntrySize` bytes, refused with `400-28` past it (the same refusal whether the remote field is indexed or scanned). The whole pipeline may build at most `aggregateMaxResultBytes` (default `auto`, a quarter of the JVM's max heap): the strings every `MAP` adds through `CONCAT` and string `CAST`s, the values its `SCRIPT` mid-operators add (measured as their JSON text), each `JOIN` row's joined array (counted once per row, even when rows share it), the modelled heap of every document a step holds while it works (the rows a `GROUP_BY`, an unbounded `SORT`, a `JOIN`'s two sides and `DISTINCT`'s seen values keep) and the modelled heap of every result row are totalled per request, and past it the pipeline stops with `400-36` instead of running the server out of heap. The rows a bounded `SORT` (one followed by a `LIMIT` of at most 4096) keeps are charged while it keeps them, and every group a `GROUP_BY` builds is charged its map entry, key, list and output row as well as its documents. Every running pipeline together may hold at most `aggregateTotalHeldBytes` (default `auto`, a quarter of the heap), counted the same way; a pipeline that would pass it stops with `503-20`, and retrying it is safe. The heap of the first 64 rows of a step is measured exactly, then every 64th row is measured and the others are charged the running average. A `LISTEN` re-run and a read inside a transaction are held to the same budget. A `GROUP_BY` followed directly by `COUNT` only counts the distinct values (from the index when the field has one), without holding the documents. A `JOIN` on an unindexed remote field streams the remote collection and keeps only the documents whose value one of the local rows names. `GROUP_BY`, `DISTINCT` and `JOIN` key their lookups on a hash seeded per process, and so do the index reads and the `CREATE_INDEX` build behind them, so values that collide under Java's string hash cannot pile into one bucket.

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

**Aggregation step types:**

| Step | Required fields | Notes |
|---|---|---|
| `FILTER` | `operator` | Field or conjunction operator; a field operator needs `field`, `fieldOperatorType` and `value` |
| `MAP` | `operators` (non-empty) | Each operator needs `fieldName`; a dotted `fieldName` writes or removes a nested field |
| `GROUP_BY` | `fieldName` | A dotted `fieldName` emits its key nested: `a.b` → `{"a":{"b":…},"group":[…]}` |
| `JOIN` | `joinCollection`, `localField`, `remoteField`, `asField` | `joinCollection` must satisfy naming rules; the user must also have `READ` on `joinCollection`; a dotted `asField` nests the joined array |
| `COUNT` | — | Returns `{"count": N}` |
| `DISTINCT` | — | `fieldName` is optional; omitting it deduplicates whole documents; a dotted `fieldName` emits its value nested |
| `LIMIT` | `limit` (> 0) | |
| `SKIP` | `skip` (>= 0) | |
| `SORT` | `fieldName`, `ascending` | |
| `REDUCE` | `script` | Folds the whole stream into one document; optional `initialValue` (default JSON null) and `resultField` (default `value`, nested when dotted) |

A field name a step *writes* follows the same dotted-path rule every step uses to *read* one, so a later `FILTER`, `SORT` or `GROUP_BY` on the same name finds what an earlier step wrote. A non-object value standing where the path needs an object is replaced by one in the step's output (never in the stored document).

**Result order.** A pipeline that ends in `SORT` — or whose last order-determining step is a `SORT` — is fully ordered. Otherwise the order in which documents are enumerated is unspecified, so a `LIMIT` or `SKIP` used as the pipeline's source returns an unspecified subset, exactly as a SQL `LIMIT` without an `ORDER BY` does. `REDUCE` is the exception, because its *value* and not just its row order depends on the enumeration: when `REDUCE` is the pipeline's source it folds in a defined order (by page, then by `_id`), so a non-commutative fold answers the same before and after a restart or an eviction. Put a `SORT` in front of `REDUCE` to choose a different order. After any other preceding step (`FILTER`, `MAP`, `JOIN`, `GROUP_BY`, `DISTINCT`, …) it folds by `_id`, and rows that have no `_id` — `GROUP_BY` and `DISTINCT` output — follow in a canonical order of their content, so the fold answers the same whether a step was resolved through an index or a scan. The arrays a step builds are ordered too: a `GROUP_BY` group and a `JOIN`'s `asField` list their documents by `_id`, so the same query answers identically whether it was resolved through an index or a full scan.

`GROUP_BY`, `JOIN`, `SORT`, and `DISTINCT` use a single-field index when one exists on the step's field and the step is the first step in the pipeline; otherwise they fall back to a full scan. These steps use only the scalar/custom/null indexes, so documents whose indexed field holds a JSON object or array are not represented in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results (see [Memory management → Streaming reads](../README.md#memory-management)). 
Object- and array-valued fields are instead indexed for **element-match** (whole-value equality): a `FILTER` with `EQUALS`, `NOT_EQUALS`, `IN`, or `NOT_IN` hashes the object/array and resolves it through a dedicated per-kind hash index (`…-Object.idx` / `…-Array.idx`). A value is keyed by the first 128 bits of the SHA-256 of its canonical form, written as 26 lowercase base32 characters; every hit is re-tested against the stored document.

A collection may also carry a single JSON Schema stored alongside its data files as `{collection}-schema.json` (see [Schema validation](#schema-validation)). Ordering/containment operators (`GREATER_THAN*`, `SMALLER_THAN*`, `CONTAINS`) and the reconstructing steps above cannot use these hash indexes because a hash cannot be ordered or turned back into a value.

**Field operator types:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`

`EQUALS`, `NOT_EQUALS`, `IN` and `NOT_IN` all share one notion of equality: strings compare case-insensitively, custom types compare semantically (so `#datetime(2024-01-01T10:00)` equals `#datetime(2024-01-01T10:00:00)`), numbers compare by value, and objects and arrays compare as whole values — element by element, where a nested custom value still compares semantically and a nested string compares exactly. The same holds for `CONTAINS` of a scalar in an array-valued field, except that a string element there compares exactly. An object/array hash index written before semantic hashing of nested custom values needs one `REINDEX` of that field. The primary key is the one exception — a `FILTER` on `_id` compares exactly, matching `FIND_BY_ID`, `SAVE` and `DELETE`. `CONTAINS` on a string is a case-sensitive substring test. A `null` operand is only ever an equality test: `EQUALS null` returns exactly the documents whose field is `null` and `NOT_EQUALS null` exactly those whose field is not, while the ordering operators and `CONTAINS` return nothing. Against a non-null operand, a document whose field is `null` is matched by `NOT_EQUALS` — `null` equals no other value — and by no other operator. In an `IN`/`NOT_IN` list, `null` is a value like any other: a `null` field is a member of a list holding `null` (`IN [1, null]` matches it, `NOT_IN [1, null]` does not) and of no other list. A document that lacks the field is excluded by every operator.

`SORT` orders by that same comparison, so the ordering operators and the sort agree: a custom type is ordered by its own comparator (chronologically for `#datetime`/`#time`, by geohash for `#geo`, by SimHash signature for `#vector`), never by the text of its wire form. Two values the comparison calls equal — `#datetime(2024-01-01T10:00)` and `#datetime(2024-01-01T10:00:00)` — therefore tie in a `SORT` and break on `_id`, exactly as two equal numbers or strings do. On a field that holds both plain strings and custom values, every custom value sorts after every plain string; values of different types are ranked boolean, number, string, custom, and that ranking is identical on every node.

Every field operator requires a `value`, including the ones whose operand is conceptually empty — omitting the member is refused with `400-1` rather than being read as a null operand. Spell a null operand as an explicit JSON `null` (`"value": null`).

**Conjunction operator types:** `AND`, `OR`, `NOR`, `XOR`, `NAND`

### The `CAST` mid-operator

A `MAP` `ADD_FIELD` may carry a `CAST` mid-operator (`fieldName`, `toType`, plus `customTypeName` for `JSON_CUSTOM`). A cast that cannot produce a value of the target type sets the field to `null` rather than to a manufactured one, on every target type:

- `NUMBER` accepts only the number syntax the wire protocol itself accepts — digits, a leading `-`, a decimal point, and an `e`/`E` exponent. Java-only spellings (`0x1p3`, `1d`, `+1`), surrounding whitespace, `Infinity` and `NaN` all answer `null`, as does an exponent that overflows a double. An integral result narrows exactly as a wire-parsed number does, so a cast `5` and a stored `5` are indistinguishable.
- `BOOLEAN` accepts `true` and `false` case-insensitively; every other string answers `null`. A number casts to `value != 0`.
- `STRING` renders a number with the same formatter that writes documents, and a custom type as its data value.
- `JSON_CUSTOM` answers `null` when the source string is not a valid value of the named type.

### The `CONCAT` mid-operator

`CONCAT` joins its operands into one string, which may be at most `maxEntrySize` characters long; a longer result stops the pipeline with `400-28`. The strings one `MAP` step adds to a document through `CONCAT` and string `CAST`s are also totalled per document, and a document whose additions pass `maxEntrySize` stops the pipeline with `400-28` too, however small each one is. A `SCRIPT` mid-operator's value counts the same way, measured as its JSON text. A top-level string operand is a **field path** unless it carries the string-literal prefix `-`; a string inside an array operand is always a literal. A `null` operand contributes the text `null`, and so does a top-level field path the document does not hold — in both operand positions, and whether the null was written literally or read from a null-valued field.

### Script operators (SimpleJS in the pipeline)

A pipeline may run SimpleJS in three places. All three are gated by `scriptsEnabled` (the same master switch `RUN_SCRIPT`, procedures, triggers and schedules sit behind) **and** by the caller's per-database script grant: admin, database owner, or `scriptPermissions` for the database — a `READ` grant alone is not enough, because executing code is a strictly wider capability than reading. A pipeline script is refused inside a `LISTEN` (`400-19`): that pipeline re-runs on every matching write, with no client-visible budget.

- A **`SCRIPT` filter operator** — a predicate, `(doc) => boolean`. Recognised by a `script` key (instead of `fieldOperatorType`/`customOperatorName`/`conjunctionType`) and usable anywhere a field operator can appear, nested conjunctions included.

```json
{"type":"FILTER","operator":{"script":"export default (doc) => doc.price * doc.qty > 100;"}}
```

- A **`SCRIPT` mid-operator** in a `MAP` `ADD_FIELD` — a computed field, `(doc) => value`. An `undefined` result leaves the field unset.

```json
{"type":"MAP","operators":[
  {"fieldName":"total","operator":{"type":"SCRIPT","script":"export default (doc) => doc.price * doc.qty;"}}]}
```

- A **`REDUCE` step** — a fold, `(accumulator, doc) => accumulator`, collapsing the stream to one document exactly as `COUNT` does. A step after it is legal and operates on that single document.

```json
{"type":"REDUCE","resultField":"total","initialValue":0,
 "script":"export default (acc, doc) => acc + doc.price * doc.qty;"}
```

The callable is the module's `export default`, else its top-level `return` value; an async or generator function is refused. **A pipeline script has no `db` module, no `fetch`, no procedure imports and no `importText`** — it is a pure function from a document to a value. That is what makes it safe: the pipeline already holds collection read locks on the calling thread, and with no `db` a pipeline script cannot issue an `AGGREGATE`, so there is no recursion to bound.

The sandbox is **per pipeline, not per document**: one interpreter serves the whole query, so `aggregationScriptInstructionBudget` (`400-11`) and `aggregationScriptTimeoutMs` (`408-1`) bound the query as a whole rather than resetting on every row — a runaway predicate aborts the query instead of getting a fresh budget on each document. `aggregationScriptMaxSourceBytes` caps one operator's source (`400-10`), and a script that throws surfaces as `400-9`. A `SCRIPT` predicate **can never use an index**, so put it after an index-backed `FILTER`; inside a conjunction the other operands still resolve via index and the script then sees only what they produce. `analyze` reports `scriptInvocations`/`scriptMillis` and suggests exactly this.

### Custom operators (type-specific)

Besides the field and conjunction operators above, a `FILTER` operator may be a **custom operator** — a comparison defined by a custom data type rather than one of the generic field operators. A custom operator is recognised by a `customOperatorName` (instead of `fieldOperatorType`/`conjunctionType`) and is evaluated by the stored value's type. Custom operators can appear anywhere a field operator can, including nested inside a conjunction.

The **geo type** (`#geo(lat,lng)`, latitude −90..90, longitude −180..180) provides two custom operators:

- **`distance`** — compares the great-circle (haversine) distance in **metres** between the stored geo point and a target geo (`value`) against a threshold (`distance`) using a `comparator` (one of `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `EQUALS`).

```json
{"type":"FILTER","operator":{
  "customOperatorName":"distance",
  "field":"location",
  "value":"#geo(40.71,-74.0)",
  "comparator":"SMALLER_THAN",
  "distance":1000
}}
```

- **`within`** — point-in-polygon: matches when the stored geo point lies inside the enclosed shape formed by an array of at least three geo points (`polygon`).

```json
{"type":"FILTER","operator":{
  "customOperatorName":"within",
  "field":"location",
  "polygon":["#geo(40.0,-74.2)","#geo(40.9,-74.2)","#geo(40.9,-73.7)","#geo(40.0,-73.7)"]
}}
```

Geo values are stored and read through ordinary `SAVE`/`FIND_BY_ID`/`AGGREGATE` (e.g. `"location":"#geo(40.71,-74.0)"`), and a geo field is indexable like any other. `EQUALS`/`NOT_EQUALS`/`IN` on a geo value resolve through the standard field index. The spatial operators use the index too: the field index is sorted by geohash, so `within` and `distance` with a `SMALLER_THAN*` comparator pre-filter candidates with a geohash **bounding box** and then re-test each fetched document exactly (so a hit is always confirmed). `distance` with a `GREATER_THAN*` comparator selects points *outside* a box, which a bounding box cannot prune, so it falls back to a full scan. A `COUNT` after a geo filter always reads the matched documents (the spatial candidates cannot be counted from ids alone).

The **vector type** (`#vector(v0,v1,...,vn)`, a dense vector of numbers) provides one **ranking** custom operator for semantic search:

- **`nearest`** — returns the `k` documents whose stored vector is most similar to a query vector (`value`) by **cosine similarity**, ordered by descending similarity. Unlike the geo operators (which are boolean predicates), `nearest` is a ranking + limit step: it scores the candidate documents and keeps only the top `k`.

```json
{"type":"FILTER","operator":{
  "customOperatorName":"nearest",
  "field":"embedding",
  "value":"#vector(0.12,0.44,0.91)",
  "k":10,
  "exact":false
}}
```

Vector values are stored and read through ordinary `SAVE`/`FIND_BY_ID`/`AGGREGATE` (e.g. `"embedding":"#vector(0.12,0.44,0.91)"`), and a vector field is indexable like any other. The vector field index is sorted by a **SimHash (locality-sensitive) signature**, so `nearest` pre-filters candidates from the neighbourhood of the query's signature in the sorted index and then re-scores each fetched document exactly. The index keys each vector by its 16-bit signature and a 64-bit hash of its components (20 characters, whatever the dimension), not by the vector itself, so it stays small enough to cache: an `EQUALS`/`IN` on a vector field uses it only to nominate candidates, which are re-tested against the stored documents, and every other operator, `COUNT`, `DISTINCT`, `GROUP_BY` and `SORT` on that field read the documents. Because SimHash is locality-sensitive but not exact, this index path is **approximate (ANN)**: a query may miss matches that fall outside the scanned neighbourhood. Pass `"exact":true` to force an exact full scan instead (guaranteed top-`k`, no index). A `COUNT` after a `nearest` filter always reads the matched documents (the ranked candidates cannot be counted from ids alone), and returns `min(k, matches)`.

### Explain / Analyze

Send `"analyze": true` on an `AGGREGATE` request (default `false`) to get back diagnostics about how the query ran, alongside the normal `results`. The diagnostics arrive in an `analyzeResult` object that is present **only** when `analyze` is `true`.
No extra permissions are required — the regular `READ` access for `AGGREGATE` is enough. Analyze applies only to `AGGREGATE`.

```json
{"type":"AGGREGATE","databaseName":"my_db","collectionName":"my_coll","analyze":true,
 "aggregationSteps":[{"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"status","value":"active"}}]}
```

Response (the `analyzeResult` object):

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

| Field | Description |
|---|---|
| `startTime` / `endTime` | Epoch milliseconds bracketing processing — measured after parsing/validation/authorization and stopped right after the operation returns |
| `durationMillis` | `endTime − startTime` |
| `indexUsed` / `indexesUsed` | Whether any field index was used, and the names of the fields whose indexes were used |
| `documentsScanned` | Number of documents read/examined while running the pipeline |
| `locksAcquired` | The locks taken: collection-level (`db\|coll`) and field-index (`db\|coll\|field`). Empty of collection locks for a dirty read |
| `suggestions` | Query advice (see below) |

Two kinds of suggestions are produced:

- **No index used** — when no field index was used, it states so and recommends creating an index on the fields referenced by the pipeline's index-capable steps (`FILTER`, `SORT`, `GROUP_BY`, `JOIN` remote field, `DISTINCT`).
- **`FILTER` not first** — when a `FILTER` step is not the first step, it recommends moving it to the top of the pipeline so it can use an index and reduce the number of documents scanned.

Returned even when there are no results: in analyze mode an empty result set still returns an `OK` response carrying `analyzeResult` (rather than the usual `404-3` *No results*).

### `CREATE_INDEX`
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```
Several fields can be indexed with one request through `fieldNames`, which builds every field that is not indexed yet
in one pass over the collection instead of one pass per field:
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldNames":["email","status","age"]}
```
`fieldName` and `fieldNames` may be combined; their union is indexed. A field already indexed is skipped, not rebuilt,
and the message names it (`Created index for fields: status, age; already existed for field: email`). `fieldNames`
must not be empty (`400-1`), and one invalid, blank or reserved name refuses the whole request (`400-1`) before anything
is built, as does a name that collides with another one on disk (`409-11`) or a request that would take the
collection past `maxIndexesPerCollection` (`400-42`).

Answers `404-11` when the collection does not exist (`503-10` on a cluster node it has not reached yet), like the write operations; `script_runs` can be indexed once the first recorded run has created it. Creating an index that already exists answers `OK` without rebuilding it. Creating one past `maxIndexesPerCollection` is refused with `400-42`.

### `DROP_INDEX`
```json
{"type":"DROP_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

### `REINDEX`
Rebuilds field indexes from the authoritative document store (recovers from a background-processing failure that left an index stale).
`fieldNames` is optional; omit it to rebuild every registered index on the collection, or pass a subset to rebuild only those.
Requires `READ_WRITE`.
```json
{"type":"REINDEX","databaseName":"my_db","collectionName":"my_coll","fieldNames":["email"]}
```
The response lists the fields that were rebuilt:
```json
{"type":"REINDEX","status":"OK","message":"Rebuilt 1 index(es)","rebuiltFields":["email"]}
```
Returns `404-6` if a named field has no registered index.

`"primaryKey": true` also rebuilds the collection's primary-key index (`pk.idx`) from its page files, which are
authoritative, and then its page-occupancy metadata. It is the repair for a `pk.idx` that cannot be read (every
read and write of the collection answers an error until then), that points at the wrong bytes, or that lost or
kept a record the pages say otherwise about. The rebuild first checkpoints the collection's write-ahead log, so every
logged write is on the pages it scans. When the old `pk.idx` cannot be read, a vacuum intent (`.intents`) that startup
left for that reason is settled first by removing the vacuum's temporary file. When it can be read, any intent still
on disk was left by a vacuum this process could not clean up after, so the rebuild refuses with `409-15` and names
it; a restart recovers it. The page scan skips holes, the blank lines an update or delete leaves behind.
```json
{"type":"REINDEX","databaseName":"my_db","collectionName":"my_coll","primaryKey":true}
```
```json
{"type":"REINDEX","status":"OK","message":"Rebuilt the primary-key index (2 entries)","rebuiltFields":[],"primaryKeyEntries":2,"unversionedEntries":0}
```
- A page record does not carry its write version, so each entry keeps the version the old `pk.idx` held for it.
  An id the old file did not name, or every id when the old file could not be read, gets version `0`
  (`unversionedEntries` counts them). Under clustering that lets any other node's copy or tombstone outrank it.
- Field indexes are not rebuilt unless `fieldNames` names them: the ids the rebuild found or dropped are queued
  for the background index worker, and index-backed reads stay correct meanwhile. When the old `pk.idx` could not
  be read there is nothing to compare against, so every registered field index is rebuilt and listed in
  `rebuiltFields`.
- If a page holds a line that is not a record, the same `_id` twice (on one page or across pages), or a torn last
  record, the rebuild refuses with `409-15` and leaves `pk.idx` untouched, rather than guess which copy is real.
- The field-index work is recorded before the new `pk.idx` is written: the changed ids are marked pending, and every
  field the request will rebuild is marked as half-built. A process killed in between therefore restarts with the
  unclean-stop warning and with those fields answered by a scan until the next `REINDEX`, never with a stale index.
- Admin databases cannot be reindexed.

Under clustering, `REINDEX` rebuilds on every node: the coordinator rebuilds its own indexes, then asks every
peer to do the same, and answers `503-3` when a majority did not confirm (the rebuild still stands where it ran).

### `SAVE_SCHEMA`
Attaches (create-or-replace) a **JSON Schema (draft 2020-12)** to a collection so that every subsequent `SAVE`/`BULK_SAVE` document must comply (see [Schema validation](#schema-validation)). A collection has **at most one** schema. Requires admin privileges or database ownership.
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
The response echoes any non-fatal `warnings` (e.g. unrecognized keywords that were ignored):
```json
{"type":"SAVE_SCHEMA","status":"OK","message":"Collection schema saved successfully","warnings":[]}
```
Returns `400-8` if the supplied schema is not a valid 2020-12 schema, and `400-24` if it is valid but expands to more than 100 000 schema nodes once its `$ref`s are followed (each reference counts its target again, so a chain of `$defs` whose levels are `allOf:[{$ref:next},{$ref:next}]` doubles per level). It returns `400-39` if the schema holds more than `schemaMaxPatterns` regular expressions (default `256`, every `pattern` value and `patternProperties` key counted through `$ref`s), and `400-31` if one of them is longer than `schemaMaxPatternLength` characters (default `4096`); both are checked before any pattern is compiled or the schema is meta-validated. Nothing is stored in any of these cases. These limits are checked only by `SAVE_SCHEMA`: a schema stored before they existed, or replicated from a peer configured with higher ones, still loads (the node logs a warning) and is bounded at write time by `schemaValidationBudget` instead; its patterns longer than `schemaMaxPatternLength` are compiled on every use and never cached.

### `DELETE_SCHEMA`
Removes the collection's schema, so writes are no longer validated. Idempotent — returns `OK` whether or not a schema existed. Requires admin privileges or database ownership.
```json
{"type":"DELETE_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```

### `GET_SCHEMA`
Returns the collection's schema exactly as it was saved. Requires `READ` on the collection.
```json
{"type":"GET_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```
```json
{"type":"GET_SCHEMA","status":"OK","message":"Ok","schema":{"type":"object","required":["_id"]}}
```
Answers `404-12` when the collection has no schema, `404-11` when the collection does not exist, and `503-11` when
the stored schema cannot be read (an unreadable schema is reported, never mistaken for no schema).

### `LISTEN`

Subscribe to live query results. The server runs the aggregation once and returns the initial results with a UUID listen ID and a SHA-256 hash of the result set. Whenever documents in the collection change, the server re-runs the query in the background; if the new hash differs from the last sent hash, a push message is sent to the same connection with the updated results.

Accepts the same `aggregationSteps` as `AGGREGATE` (all step types, same validation rules). Requires `READ` on the base collection; `JOIN` steps additionally require `READ` on each joined collection. A connection may hold at most `maxListensPerConnection` (default `64`) registrations and the server at most `maxListens` (default `10000`); past either, `LISTEN` answers `400-25` without running the query. One user may hold at most `maxListensPerUser` (default `256`, `0` = unlimited) across all of their connections; past it, `400-38`. A registration keeps its request for as long as it lives, so a `LISTEN` line longer than `listenMaxRequestBytes` (default `1Mb`) is refused with `400-37` before it runs, and the requests every registration keeps (counted as each serialized request's length) may total at most `listenMaxRetainedBytes` (default `64Mb`) on the server; past it, `LISTEN` answers `503-15` until registrations end. The bytes and the per-user count are given back when a registration stops, ends, or its connection closes. Each background re-run is bounded by `listenRerunTimeoutMs` (default `10000`) rather than `aggregateTimeoutMs`; a re-run past it is logged and pushes nothing, and the next change to a collection it reads re-runs it again. Identical registrations (the same user listening with the same query, on one connection or many) share one re-run: the query runs once per change and every registration whose last pushed hash differs gets its own frame, carrying its own `listenId`. Re-runs of distinct queries spread over `listenThreads` workers (default `2`).

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

Initial response:
```json
{
  "type": "LISTEN",
  "status": "OK",
  "message": "Listening for changes",
  "errorCode": null,
  "listenId": "550e8400-e29b-41d4-a716-446655440000",
  "results": [ "..." ],
  "resultHash": "a3f5...64-hex-chars...d91e",
  "isUpdate": false
}
```

Push message (sent asynchronously when results change; `isUpdate: true` is what tells it from a reply, see
[Matching replies to requests](#matching-replies-to-requests)):
```json
{
  "type": "LISTEN",
  "status": "OK",
  "message": "Query results updated",
  "errorCode": null,
  "listenId": "550e8400-e29b-41d4-a716-446655440000",
  "results": [ "..." ],
  "resultHash": "b7c2...64-hex-chars...f308",
  "isUpdate": true
}
```

The background re-run uses dirty reads (skips collection-level read lock) for timeliness. All listen registrations for a client are automatically removed when the connection closes.

A listen the server ends on its own is told so with one final frame, and nothing is pushed for it afterwards. That happens when a collection it reads (its own or a `JOIN` target) is dropped, when its database is dropped, or when the listening user can no longer read it. The frame is always written after the listen's initial response:

```json
{
  "type": "LISTEN",
  "status": "NOT_FOUND",
  "errorCode": "410-1",
  "message": "The listen ended: a collection it reads was dropped",
  "listenId": "550e8400-e29b-41d4-a716-446655440000"
}
```

A `STOP_LISTEN` or a closed connection ends a listen without this frame.

### `STOP_LISTEN`

Cancel a specific listen subscription by its ID.

```json
{"type": "STOP_LISTEN", "listenId": "550e8400-e29b-41d4-a716-446655440000"}
```

Returns `NOT_FOUND` (`404-7`) if the listen ID is not registered.

### Transactions

A connection may open **one transaction at a time**. While a transaction is open the connection's data mutations (`SAVE`, `BULK_SAVE`, `DELETE`) are **buffered** instead of applied: they are recorded in an internal `admin/transactions` collection and are not written to the real collections until commit.

The connection's own reads (`FIND_BY_ID`, `AGGREGATE`) see its buffered writes (**read-your-writes**); other connections do not. The first buffered write to a collection takes that collection's exclusive write lock and holds it until commit/rollback, so no other connection can read or write those collections meanwhile (this is what preserves atomicity). 

See [Concurrency & locking](../README.md#concurrency--locking).

While a transaction is open only `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID`, `AGGREGATE`, `COMMIT_TRANSACTION`, `ROLLBACK_TRANSACTION` and `CLOSE_CONNECTION` are accepted; any other operation is rejected with `409-6`. 

If a buffered write cannot take a collection's write lock within `transactionLockTimeoutMs` the transaction is aborted: that statement answers `409-5`, its buffered writes are discarded and its locks released. The transaction stays open but unusable — every further `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID` and `AGGREGATE` answers `409-9`, and so does `COMMIT_TRANSACTION`. Send `ROLLBACK_TRANSACTION` to end it and start again. This is deliberate: a retried statement must never silently commit on its own outside the transaction the client still believes it is in.

If the connection closes with a transaction still open, it is automatically rolled back.

**Idle transactions.** A transaction's write locks block every other connection on its collections, so a connection may not sit silent while it holds one. If no complete request arrives within `transactionIdleTimeoutMs` (default `30000`; a line dribbled in byte by byte does not count until it ends), the server rolls the transaction back on the connection's own thread: its buffered writes are discarded and its locks released, so blocked writers proceed. The connection stays open. Its **next request, whatever it is, is answered `409-16` once and not executed** — including `COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` — so a client that still believes it is inside the transaction cannot have a write run on its own by mistake. Only `CLOSE_CONNECTION` and `SERVER_INFO` are exempt: `SERVER_INFO` is answered normally and leaves the `409-16` for the next request. After that one answer the connection behaves normally and a new `START_TRANSACTION` works. A transaction whose commit is half-applied (`500-33`) or that was already aborted (`409-5`) is never rolled back this way. The timeout only runs while the server waits for the next request: a long-running request is never cut short.

```json
{"type":"SAVE","status":"ERROR","message":"The transaction was rolled back after being idle too long","errorCode":"409-16"}
```

**Maximum age.** A transaction older than `transactionMaxAgeMs` (default `600000`, `0` = no limit) is rolled back the same way however busy the connection is, so a client cannot hold its write locks forever by sending a request every few seconds. Its next request is answered `408-5` once and not executed, exactly like the idle case.

**Size.** One transaction may buffer at most `transactionMaxOps` operations (default `100000`; a `BULK_SAVE` counts one per document) and `transactionMaxBytes` of documents (default `256Mb`). A buffered `DELETE` counts the bytes of what it buffers: its id, plus the whole deleted document when a `DELETED` trigger will receive it. The write that would pass either is refused with `400-26`; nothing of it is buffered and the transaction stays usable, so the client can still commit what it buffered or roll back. Every open transaction together may buffer at most `transactionTotalMaxBytes` (default `auto`, a quarter of the heap); a write that would pass it answers `503-21`, buffers nothing and leaves its transaction usable, and retrying it is safe once another transaction has ended.

**Grants are checked again at commit.** Every operation is authorized when it is buffered, and `COMMIT_TRANSACTION` authorizes every collection the transaction writes once more against the user's current grants. If a grant was revoked in between, the commit is refused with `403-1`, the whole transaction is rolled back and nothing is applied.

#### `START_TRANSACTION`
```json
{"type":"START_TRANSACTION"}
```
Response carries the new transaction id:
```json
{"type":"START_TRANSACTION","status":"OK","message":"Transaction started","transactionId":"<uuid>"}
```
Returns `409-3` if a transaction is already open on the connection.

#### `COMMIT_TRANSACTION`
Applies the buffered operations to the real collections in order, then removes the buffered records and releases the held locks.
```json
{"type":"COMMIT_TRANSACTION"}
```

#### `ROLLBACK_TRANSACTION`
Discards the buffered operations (nothing is written to the real collections), removes the buffered records and releases the held locks.
```json
{"type":"ROLLBACK_TRANSACTION"}
```
`COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` return `409-4` if no transaction is open.

#### `LIST_TRANSACTIONS` (admin only)
Discovers in-doubt distributed (2PC) transactions **cluster-wide** — the ones that have prepared but not yet resolved because their coordinator was lost.
Runs only when clustering is enabled (standalone returns this node's local view). It fans a query out to every live member and aggregates the results by distributed-transaction id.
This is the diagnostic input to `RESOLVE_TRANSACTION`.
```json
{"type":"LIST_TRANSACTIONS"}
```
Response (`transactions` is the aggregated list; each row describes one stuck transaction):
```json
{
  "type": "LIST_TRANSACTIONS",
  "status": "OK",
  "transactions": [
    {
      "dtxId": "<uuid>",
      "coordinator": "10.0.0.11:9990",
      "coordinatorReachable": false,
      "participants": ["10.0.0.12:9990", "10.0.0.13:9990"],
      "ageMs": 42000,
      "perNodeStatus": {"10.0.0.12:9990": "PREPARED", "10.0.0.13:9990": "PREPARED"}
    }
  ]
}
```

#### `RESOLVE_TRANSACTION` (admin only)
Manually forces the outcome of an in-doubt distributed transaction identified by `dtxId` (from `LIST_TRANSACTIONS`). `decision` must be `"commit"` or `"abort"`; the decision is broadcast to all members so every participant applies it. Use only after confirming the correct outcome — see [docs/clustering.md](clustering.md).
```json
{"type":"RESOLVE_TRANSACTION","dtxId":"<uuid>","decision":"commit"}
```
```json
{"type":"RESOLVE_TRANSACTION","status":"OK","message":"Transaction committed"}
```
Returns `400-1` if `dtxId` is missing or `decision` is not `commit`/`abort`.

### `RUN_SCRIPT`
Runs a JavaScript program inside the database (see [docs/simplejs.md](simplejs.md) for the engine). The script is **scoped to one database** — the `databaseName` of the request — and may use any collection in it. Enabled by default; set `scriptsEnabled=false` to refuse the operation with `403-2`. Being allowed to run one still requires admin privileges, database ownership, or `scriptPermissions` on that database.

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

- `script` (required) is the program source and `args` (optional) an arbitrary object the script reads through `import args from "args"`. `db.name` is the scoped database, so one script can run anywhere without hardcoding it.
- `result` is the script's value: a top-level `return`, else `export default`, else an object of the named exports, else JSON `null`. A promise returned at top level is awaited; one that never settles fails the run with `400-20`.
- `logs` holds the newest `scriptMaxLogLines` lines of `console` output, each clipped to `scriptMaxLogLineChars`, with `logsTruncated` reporting whether anything was dropped. `metrics` reports instructions, peak bytes, `db` operations and duration, each beside its budget. `stack`, on a failure, lists the frames the error came from innermost first (`"applyRule (procedures/rules:12:7)"`), capped at 32. All three are returned on **every** outcome, including a failure — which is when they matter most. A sandbox abort carries no frames, being no program error.
- `runId` names the run while it executes, so a slow one can be found with [`LIST_SCRIPTS`](#list_scripts-admin-only) and stopped with [`CANCEL_SCRIPT`](#cancel_script-admin-only).
- **Permissions**: admins on any database, database owners on theirs, anyone else with a per-database grant (`scriptPermissions: {"mydb": "RUN"}`). Starting a script is a separate capability from what it may do — every operation it issues is authorized again on its own request against the caller's own permissions, and the collection schema still applies to its writes. A script cannot leave its database (`admin` included).
- **The exposed surface is read+write only**: `findById`, `aggregate`, `cursor`, `save`, `bulkSave`, `delete`, `listCollections`, `listDatabases`, `transaction`. DDL and user management are not reachable. Every failed operation throws a catchable `Error` inside the script; only genuine absence is a value (`findById` → `null`, `aggregate` → `[]`, deleting a missing document is a no-op).
- **Reading more than fits in memory**: `db.cursor(database, collection, pipeline, options)` returns an iterator that runs the pipeline one batch at a time, so only one batch counts against `scriptMaxMemoryBytes`. It is a paged read over a live collection, not a snapshot, and only meaningful with a `SORT` step.

  ```js
  import db from "db";
  let total = 0;
  for (const order of db.cursor(db.name, "orders",
          [{ type: "SORT", fieldName: "_id", ascending: true }], { batchSize: 500 })) {
      total += order.amount;
  }
  return total;
  ```
- **Importing shared code**: a script may import any stored procedure of its own database with `import … from "procedures/<name>"` — a library is just a procedure that `export`s instead of returning. `SAVE_PROCEDURE` refuses an import that does not resolve (`400-18`), so libraries must be installed before their users. A caller without script `MANAGE` (or ownership, or admin) on the database runs imported code but cannot read it: `toString()` of an imported function or class answers the `function name() { [native code] }` stub. Only `export`ed bindings are visible, so a procedure written in the `CALL_PROCEDURE` style (a top-level `return`) imports as `undefined`.
- **Outbound HTTP** is available: `scriptFetchEnabled` is on and `scriptFetchAllowlist` is `*`, which means any **public** host. Every address a host resolves to is checked and the connection goes to that checked address, so loopback, private (10/8, 172.16/12, 192.168/16), link-local (the 169.254.169.254 metadata endpoint), CGNAT, multicast, reserved and unique-local addresses are refused with `fetch: host not allowed` — even through a DNS name or a later DNS answer — unless the **exact** host is listed (`10.0.0.5`, `localhost`); a `*.example.com` wildcard never re-opens them, and listing an internal host logs a SECURITY WARNING at startup. Only `http`/`https` URLs are fetched, redirects are never followed, the body is read as a stream and abandoned past `scriptFetchMaxResponseBytes`, a run may have at most `scriptFetchMaxConcurrent` (default 8) fetches in flight (one more rejects with `fetch: too many concurrent requests`), and any other network failure rejects with the bare `fetch failed` — the cause goes to the server log, not to the script. `*` still lets any script make the server call any public host, and the server warns at every startup — **narrow it**. `fetch` stays unavailable to pipeline scripts and before-write hooks, which hold collection locks.
- **Native work is budgeted too**: a builtin's own loop (`indexOf`, `includes`, `splice`, typed-array scans…) is charged about one instruction per element, so a scan over a sparse `new Array(2 ** 32 - 1)` ends with `400-11` and honours `CANCEL_SCRIPT` and the timeout; native recursion over script data (`JSON.parse`/`JSON.stringify`, `structuredClone`, `flat`, `String()` of nested arrays, and converting a returned or stored value to the host document form) stops at 256 levels with a catchable `RangeError: Maximum nesting depth exceeded`; a BigInt result over 2^24 bits (2 MiB) is a catchable `RangeError: Maximum BigInt size exceeded` and a large one (from any operator, or converted to text) is charged to `scriptMaxMemoryBytes`; `JSON.parse` is charged per value it builds, and a builtin that writes past an array's dense end is charged for the holes it pads; a locale argument (`toLocaleString`, `localeCompare`) resolves to the closest locale the JDK ships, dropping extensions and private-use subtags (`'en-x-foo'` formats as `en`, an unknown language as `scriptLocale`, an ill-formed tag is a `RangeError`); and a JVM stack or heap exhaustion inside an async function or generator ends the run with `400-12` instead of hanging it. `import … from "procedures/<name>"` resolves only a name the wire would accept (3-64 letters, digits, `_` or `-`); anything else is `Cannot find module`.
- **Run history**: unless `scriptRunHistoryEnabled=false`, a finished run is recorded in the reserved `script_runs` collection of its database, queryable with an ordinary `AGGREGATE`. Ad-hoc `RUN_SCRIPT` is opt-in via `scriptRunHistoryKinds`; the collection is created lazily and refused to client writes. Rows are written in batches (one per database), at most `scriptRunHistoryMaxQueued` wait to be written, and the sweep (every `scriptRunHistorySweepMs`) applies the retention and the `scriptRunHistoryMaxRows` cap (100,000 rows per database by default, `0` for none).
- **Errors**: `403-2` scripting disabled, `403-1` caller may not run scripts, `404-4` unknown database, `400-10` source over `scriptMaxSourceBytes`, `400-9` threw or would not parse (`message` is `"<ErrorName>: <message>"`, the name and message each clipped to `scriptMaxLogLineChars` like every stack frame; an engine failure with no script-level meaning reads `InternalError: Internal script error`, the detail going only to the server log), `400-11` instruction or depth budget, `400-12` over `scriptMaxMemoryBytes`, `400-15` result over `scriptMaxResultBytes`, `400-30` an array-like past `scriptMaxArrayLength`, `400-20` result promise never settled, `408-1` over `scriptTimeoutMs`, `408-2` cancelled, `503-6` a concurrency ceiling refused it (the `message` names which of `node`/`user`/`database`; nothing ran, so it is retryable), `503-7` the run was placed on another node that never reported an outcome — it is deliberately **not** re-run here, because it may already have run there, so retrying is only safe if the script is idempotent, `409-6` sent with a transaction open.
- Under clustering the run is **placed** on a live node chosen by script load and ownership share (`scriptRoutingEnabled`, on by default); each operation it issues is then routed to its collection's owner, and `db.transaction` spans owners through the same 2PC the wire protocol uses.

### `SAVE_PROCEDURE`
Stores a named script in a database so it can be called by name instead of being sent on every request. Requires admin privileges, ownership of the database, or `scriptPermissions: {"mydb": "MANAGE"}`. The source is **parsed at save time**, so a broken procedure is refused here rather than on somebody else's first call. Idempotent upsert: saving an existing name replaces it and bumps its `version`.

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

- `enabled` defaults to `true`; a disabled procedure is not callable (`404-8`).
- `ifVersion` is optional optimistic concurrency: present and not equal to the stored version → `409-8`. Absent means an unconditional upsert. Use `0` to require that the procedure does not exist yet.
- A procedure is stored **with its database**, in `{filePath}/{database}/.procedures/{name}.json`, so dropping the database removes it.
- **A procedure bound by another user is protected.** A [trigger](#save_trigger), before hook or [schedule](#save_schedule) looks its procedure up by name when it fires and runs it as its `definer`, so while one reaches a procedure — directly, or through a `procedures/<name>` import, transitively — saving it (disabling with `enabled:false` included) is refused with `403-4` for anyone but that definer, a database owner or an admin. A procedure that imports by a computed name (`import('procedures/' + n)`) or imports the `script` module counts as able to reach every procedure of its database, so while a foreign definer's binding reaches one, every save there is refused for other `MANAGE` users. Unbound procedures, and ones bound only by the caller, stay freely editable.
- **Errors**: `403-2` scripting disabled, `403-1` not permitted, `403-4` bound by a trigger, before hook or schedule whose definer is another user, `404-4` unknown database, `400-1` an invalid name (3–64 alphanumerics plus `_` and `-`, the same rule as a collection) or a blank script, `400-10` source over `scriptMaxSourceBytes`, `400-13` source that does not parse (the message carries the line and column), `400-18` a `procedures/<name>` import that does not resolve (so install a library before its importers), `400-34` a new procedure past `maxProceduresPerDatabase`, `409-8` version conflict.

### `DELETE_PROCEDURE`
```json
{"type":"DELETE_PROCEDURE","databaseName":"mydb","name":"recalcTotals"}
```
Idempotent — succeeds whether or not the procedure existed. Refused with `400-14` while a trigger still references it (the message names the trigger). For a caller who is not the definer of a binding that reaches the procedure (see [`SAVE_PROCEDURE`](#save_procedure)), a database owner or an admin, `403-4` is answered first and names no trigger.

### `LIST_PROCEDURES`
```json
{"type":"LIST_PROCEDURES","databaseName":"mydb","includeSource":false}
```
```json
{
  "type": "LIST_PROCEDURES",
  "status": "OK",
  "procedures": [
    {"name":"recalcTotals","sourceHash":"9f2…","version":4,"enabled":true,
     "createdAt":1756100000000,"updatedAt":1756100500000,"updatedBy":"alice"}
  ]
}
```
The summary requires `READ` on the database. `sourceHash` appears only for a caller who could read the source (script `MANAGE`, ownership or admin); a `READ`/`RUN` caller's summary omits it. `includeSource: true` adds the `source` field and also requires script `MANAGE` on the database (or ownership, or admin); a caller with only `READ`/`RUN` is refused with `403-6`.

### `CALL_PROCEDURE`
Runs a stored procedure. The permission is the same as [`RUN_SCRIPT`](#run_script)'s (`scriptPermissions` of at least `RUN`), the sandbox is the same, and the response shape matches — `result`, `logs`, `logsTruncated`, and `stack` on a failure, with the same error codes.

```json
{"type":"CALL_PROCEDURE","databaseName":"mydb","procedureName":"recalcTotals","args":{"id":"o1"}}
```
```json
{"type":"CALL_PROCEDURE","status":"OK","message":"Procedure executed successfully","result":42,"logs":[],"logsTruncated":false,"runId":"3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"}
```

- The procedure runs with the **caller's** authority, so calling one never grants more than the caller already had.
- The parsed program is cached per node keyed by `(database, name, version)`, so a repeated call does not re-parse. `procedureCacheSize` bounds its entries and `procedureCacheMaxBytes` its estimated heap.
- **Errors**: as `RUN_SCRIPT` — including `503-6` when the node is at `maxConcurrentScripts` and no permit came free within `scriptQueueWaitMs`, which is retryable because nothing ran — plus `404-8` when the procedure is absent or disabled.

### `LIST_SCRIPTS` (admin only)
Lists every script run executing **anywhere in the cluster** right now — ad-hoc `RUN_SCRIPT`s, `CALL_PROCEDURE`s, trigger runs and scheduled runs alike. It runs on the node that receives it and queries every live member, so an operator sees a run wherever [script placement](clustering.md) put it, not only on the node they connected to. An unreachable peer costs its rows a warning in the log rather than failing the listing.
```json
{"type":"LIST_SCRIPTS"}
```
```json
{
  "type": "LIST_SCRIPTS",
  "status": "OK",
  "message": "Ok",
  "scripts": [
    {
      "runId": "3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83",
      "node": "10.0.0.2:8990",
      "kind": "CALL_PROCEDURE",
      "database": "shop",
      "name": "reprice",
      "username": "alice",
      "ageMs": 18244
    }
  ]
}
```
- `kind` is `RUN_SCRIPT`, `CALL_PROCEDURE`, `TRIGGER` or `SCHEDULE`; `name` is the procedure, trigger or schedule name and is `null` for an ad-hoc `RUN_SCRIPT`.
- `username` is the authority the run has — the caller for `RUN_SCRIPT`/`CALL_PROCEDURE`, the definer for a trigger or schedule.
- `node` is the node **executing** the run, and `ageMs` is measured against a single instant so two rows that started together report the same age.

### `CANCEL_SCRIPT` (admin only)
Stops the run named by `runId` (from `LIST_SCRIPTS`, or from the `runId` on a `RUN_SCRIPT`/`CALL_PROCEDURE` response) wherever it is executing. Cancels locally if the run is here, otherwise broadcasts to every live member.
```json
{"type":"CANCEL_SCRIPT","runId":"3f6c1e2a-8b47-4d90-9a11-5c2e7d0b4f83"}
```
```json
{"type":"CANCEL_SCRIPT","status":"OK","message":"Ok","cancelled":true}
```
- A `runId` that matches nothing anywhere answers `cancelled:false` with status `OK` — the request was well-formed and the run is not running, which is the state the caller asked for. Returns `400-1` if `runId` is missing or is not a UUID.
- Cancellation is **cooperative and not catchable**: the run stops at its next instruction check or event-loop poll, its `try`/`catch` cannot intercept it and its `finally` blocks do not run — the same treatment the timeout and budget aborts get. A run blocked inside a host call therefore ends when that call returns, not instantly.
- The cancelled caller receives `408-2`; the operator issuing `CANCEL_SCRIPT` gets `OK`. An open `db.transaction` is rolled back.
- **A cancelled trigger run is dropped, not retried.** Trigger execution is otherwise exactly-once and a failed run is retried up to `triggerMaxAttempts`, but a cancellation consumes the run's pending record — cancelling a runaway trigger stops it rather than queueing it up again. See [docs/simplejs.md](simplejs.md).

### `LIST_TRIGGER_RUNS` (admin only)
Lists the trigger runs still recorded across the cluster: those pending a run, and those dead-lettered after exhausting their attempts. `admin/trigger_runs` is deliberately not replicated, so a run's record lives on exactly one node — the listing fans out to every live member and aggregates, the way `LIST_SCRIPTS` does.
```json
{"type":"LIST_TRIGGER_RUNS","status":"DEAD"}
```
```json
{
  "type": "LIST_TRIGGER_RUNS",
  "status": "OK",
  "message": "Ok",
  "runs": [
    {
      "runId": "8f0e…", "node": "10.0.0.4:8990", "status": "DEAD",
      "database": "shop", "collection": "orders", "trigger": "auditOrders",
      "procedure": "audit", "event": "UPDATED", "attempts": 3,
      "lastError": "TypeError: Cannot read properties of null",
      "ageMs": 91234, "nextAttemptAt": 0
    }
  ]
}
```
- `status` (optional) narrows the listing to `PENDING` or `DEAD`; omitting it reports both. An unrecognised value is `400-1`.
- A `PENDING` row is a run queued but not yet applied — normally transient, and replayed automatically when its node restarts. A `DEAD` row has exhausted `triggerMaxAttempts` and waits for an operator.
- An unreachable peer costs the operator that node's rows, not the whole listing.

### `RESOLVE_TRIGGER_RUN` (admin only)
Replays or discards one recorded trigger run, wherever its record lives.
```json
{"type":"RESOLVE_TRIGGER_RUN","runId":"8f0e…","decision":"replay"}
```
```json
{"type":"RESOLVE_TRIGGER_RUN","status":"OK","message":"Ok","resolved":true}
```
- `decision` is `replay` or `discard`; anything else is `400-1`, as is a missing `runId`. **`replay` resets the attempt count**, so a run whose cause has been fixed gets a full budget again rather than dead-lettering on its first failure. `discard` consumes the record, giving up on the run for good.
- A `runId` no live node holds answers `resolved:false` with status `OK` — the same treatment `CANCEL_SCRIPT` gives an already-finished run.
- A run whose documents no longer exist cannot be replayed and is discarded instead, so it does not sit in the listing forever.

### `SAVE_TRIGGER`
Runs a stored procedure around a write to a collection — **after** it commits (the default) or **before**, where it can veto or rewrite the document. Requires admin privileges, ownership, or `MANAGE` together with `READ_WRITE` on the collection — through its `collectionPermissions` entry when it has one, otherwise through the database grant — because an `after` trigger receives every written document and a `before` hook rewrites or vetoes every write. `MANAGE` with only `READ`, or with no data grant, answers `403-1`; the same holds for `DELETE_TRIGGER` and `TEST_TRIGGER`. **Changed:** `MANAGE` alone used to suffice on a collection the user had no `collectionPermissions` entry for, so a deployment that relied on that must now grant `READ_WRITE` on the collection or its database. Off by default: set `triggersEnabled=true` for triggers to fire (the DDL works either way).

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

- `timing` is `after` (the default) or `before`. The two differ in almost every respect, so they are described separately below. An omitted `timing` reads as `after`, so every trigger written before this existed keeps its meaning.
- **An `after` trigger fires once the write has committed**, asynchronously, on its own worker pool. It therefore cannot reject or modify the write — use a `before` trigger or a [collection schema](#schema-validation) for that — and its failure never reaches the writer; it is logged and counted in [`GET_DATABASE_STATS`](#get_database_stats).
- **Runs with the installer's authority** (`definer`), not the writer's, so it behaves identically no matter who wrote — which is what lets an audit trigger record a write by a user who has no access to the audit collection. Re-saving re-stamps the definer to the saving user. The definer's authority is re-checked every time the trigger fires (and when a pending run is replayed): if the definer is deleted, or no longer holds script `RUN` on the database and `READ` on the collection, the run is skipped and the trigger is **disabled** — `LIST_TRIGGERS` shows `enabled:false` with a `disabledReason` — and stays disabled until its owner re-saves it, even if the grant comes back. It never falls back to the writer or to an admin.
- **A collection holds at most `triggerMaxPerCollection` triggers** (`400-32` past it). A trigger run takes its definer's `maxConcurrentScriptsPerUser` and its database's `maxConcurrentScriptsPerDatabase` slice; refused, it is retried after a backoff without consuming an attempt, and dead-lettered only once `triggerRunRetentionMs` has passed since it fired.
- **Dropping a collection or database discards its pending and dead-lettered trigger runs**, so a later collection of the same name never receives the dropped one's documents.
- **A write inside a transaction fires when the transaction commits**, never before, so a rolled-back write fires nothing. The event is the one the write actually performed: a new document fires `CREATED`, an overwrite `UPDATED`, and a delete fires `DELETED` carrying the document as it stood when the delete was buffered.
- `mode` is `document` (one run per document, the default) or `batch` (one run for a whole `BULK_SAVE`, or one per collection and event for a transaction's writes).
- `allowCascade` defaults to `false`, so writes a trigger itself performs fire nothing. With it on, a chain terminates at `triggerMaxDepth`.
- The procedure receives `{event, database, collection, id, document, trigger, actingUser, definer, firedAt, depth}` as its `args` — `actingUser` is who wrote, `definer` is whose authority the run has. `firedAt` is the time of the first fire, the same on every retry and after a restart. In `batch` mode `documents` replaces `id`/`document`.
- Triggers are stored **with their collection**, in `{filePath}/{database}/{collection}/{collection}-triggers.json`, so dropping the collection removes them.
- **Errors**: `403-1` not permitted (including `MANAGE` without `READ_WRITE` on the collection), `404-4` unknown collection, `404-8` unknown or disabled procedure, `400-14` no events / an unknown event / an unknown mode, `400-32` a new trigger past `triggerMaxPerCollection`, `409-8` version conflict.
- **A failing run is retried** with a doubling backoff up to `triggerMaxAttempts` and then kept as a dead letter for [`LIST_TRIGGER_RUNS`](#list_trigger_runs-admin-only) / [`RESOLVE_TRIGGER_RUN`](#resolve_trigger_run-admin-only). Its warning line in the server log carries `stack=[…]`, naming the procedure and line — nobody is waiting on a response, so that log is the diagnosis.
- **There is no dry run for an `after` trigger** ([`TEST_TRIGGER`](#test_trigger) covers `before` triggers, which are directly callable). Put the logic in a procedure, exercise it with [`CALL_PROCEDURE`](#call_procedure), and make the trigger a thin wrapper that delegates to it — that exercises the body, not dispatch.

#### `timing: "before"` — validating and mutating hooks

A `before` trigger runs **synchronously, on the writing thread, inside the collection write lock, before the document is written**. It is the hook to reach for when a write has to be rejected or a field computed server-side.

```js
// procedures/normalizeOrder
export default function (document, context) {
    if (!document.customerId) {
        throw new Error("customerId is required");   // -> the write is refused with 400-21
    }
    if (context.event === "DELETED") {
        return;                                     // -> the delete proceeds
    }
    return { ...document, total: document.qty * document.price };
}
```

- The procedure is called once per document with `(document, context)`, where `context` is `{event, database, collection, trigger, actingUser, firedAt, id}`. What it returns decides the write:

  | Returned | Outcome |
  |---|---|
  | `undefined` / `null` / `true` | accept — the write proceeds with the original document |
  | a plain object | **replace** — the write proceeds with the returned document |
  | anything else (number, string, array, `false`) | refuse the write with `400-21` |
  | `throw` | refuse the write with `400-21`, the thrown message included |
  | a sandbox abort | refuse the write with `408-1` / `400-11` / `400-12` |
  | cancelled via [`CANCEL_SCRIPT`](#cancel_script-admin-only) | refuse the write with `408-2` |

- **Fail-closed.** Every failure stops the write, a timeout included: a hook that could not run must never let a write through.
- **A replacement may not change `_id`** and is **re-validated against the collection's schema** (schema validation runs before the hook, and again once the collection lock is held, so a schema saved while a write was in flight still applies to it). On a `DELETED` event there is nothing to replace, so returning a document is an error.
- **There is no `db`.** A hook runs under a held write lock, so `import db from "db"` fails inside one — the same posture a [pipeline script](#script-operators-simplejs-in-the-pipeline) has. It may still `import` a stored procedure for shared code. Writing nothing is why `mode: "batch"` and `allowCascade` are **rejected** on a `before` trigger, and why its `definer` is recorded but not enforced — deleting the definer does not disable it, unlike an `after` trigger.
- **Several hooks on one collection chain in ascending name order**, each one's output feeding the next; the first refusal stops the chain.
- **Budgets are per request, not per document.** One interpreter serves the whole request, so a `BULK_SAVE` of 10,000 documents evaluates the module body once and shares one `beforeHookInstructionBudget` and one `beforeHookTimeoutMs`. The first refusal fails the whole `BULK_SAVE`. (One exception, bounded at 2×: a batch mixing inserts and updates on a collection hooked for both runs two sets, each with its own budget.)
- **A running hook is visible to [`LIST_SCRIPTS`](#list_scripts-admin-only) and stoppable with [`CANCEL_SCRIPT`](#cancel_script-admin-only)** as one run per request, named for the collection. Cancelling refuses the write with `408-2`. Hooks are exempt from `maxConcurrentScripts` and from the per-user and per-database slices, as are `TEST_TRIGGER` and pipeline scripts (`MAP` `SCRIPT`, `REDUCE`): a write refused for want of a permit would be a failed write, not a queued one. Each is bounded by its own deadline and instruction budget instead.
- **Console output is discarded** on the write path (a hook runs once per document, so a `console.log` over a bulk save floods the log). [`TEST_TRIGGER`](#test_trigger) is the window that replaces it.
- Under clustering the hook runs on the collection's **owner**, where the write commits. An older node does not understand `timing` and would install the hook as an `after` trigger, so **roll every node** before installing a `before` trigger.

### `TEST_TRIGGER`
Runs a `before` trigger against a document you supply and reports what it would do, **without writing anything**. Requires admin privileges, ownership, or `MANAGE` with `READ_WRITE` on the collection — it executes code. Not routed: it runs on the node you are connected to.

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

- `decision` is `accept`, `replace` (with the resulting `document`) or `reject` (with a `reason` and a `stack`). A rejection is an `OK` response: the operation succeeded in telling you the hook says no. A hook that could not *run* — a sandbox abort — is an error response carrying the same code the write itself would have got.
- **Console output is captured and returned** in `logs`, unlike the live write path. That asymmetry is the point of the operation.
- **Errors**: `403-2` scripting disabled, `403-1` not permitted, `404-4` unknown collection, `404-9` unknown trigger, `404-8` unknown or disabled procedure, `400-14` an unknown event / an event the trigger does not watch / naming an `after` trigger.

### `DELETE_TRIGGER`
```json
{"type":"DELETE_TRIGGER","databaseName":"mydb","collectionName":"orders","name":"auditWrites"}
```
Idempotent — succeeds whether or not the trigger existed. Requires admin privileges, ownership, or `MANAGE` with `READ_WRITE` on the collection. A trigger installed by another user can only be deleted by that user (its `definer`), a database owner or an admin; anyone else is refused with `403-5`. The same rule applies to [`SAVE_TRIGGER`](#save_trigger) replacing an existing trigger of the same name.

### `LIST_TRIGGERS`
```json
{"type":"LIST_TRIGGERS","databaseName":"mydb","collectionName":"orders"}
```
Omit `collectionName` to list every trigger in the database. Each entry carries its `collectionName` and `definer`. Requires `READ` on the database.

### `SAVE_SCHEDULE`
Runs a stored procedure **on a clock**. Requires admin privileges, ownership, or `MANAGE`, because a scheduled run executes with its installer's authority. Unlike a trigger it needs no data grant: a schedule binds no collection and receives no document, and every `db.*` call its procedure makes is authorized again against the installer's own grants. On by default; set `schedulesEnabled=false` to refuse the three schedule operations (`403-2`) and stop anything already installed from firing. Installing one still requires `scriptsEnabled`, since a schedule can only name a stored procedure. Idempotent upsert: saving an existing name replaces it and bumps its `version`.

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

- **Exactly one of `cron` and `intervalMs`.** `intervalMs` fires every so many milliseconds; `cron` fires on the standard five-field expression `minute hour day-of-month month day-of-week`, supporting the wildcard, a single value, `a-b` ranges, `*/n` and `a-b/n` steps, comma lists, and three-letter month/day names (`JAN`, `MON`; `7` is Sunday). When both day fields are restricted they are OR-ed, the conventional cron rule. The expression is evaluated in the configured `scriptTimeZone`, not the JVM default, so `0 3 * * *` means the same instant on every node.
- **Runs with the installer's authority** (`definer`), like a trigger and for the same reason: a scheduled run has no caller, so running it as the installer makes it behave identically regardless of who happens to be connected. Re-saving re-stamps the definer to the saving user. Only the definer, a database owner or an admin may replace or delete an existing schedule; anyone else is refused with `403-5`. If the definer is deleted or no longer holds script `RUN` on the database, the occurrence is skipped and the schedule is **disabled** with a `disabledReason` until its owner re-saves it (it never falls back to an admin).
- A scheduled run takes its definer's `maxConcurrentScriptsPerUser` and its database's `maxConcurrentScriptsPerDatabase` slice; refused, that occurrence is skipped (recorded in `script_runs` as `SKIPPED`) and the next fires normally. `intervalMs` may not be below `scheduleMinIntervalMs` (`400-35`), nor may a `cron` whose consecutive occurrences can fall closer together than it (the closest pair of times within a day, or its next two occurrences), and one user may define at most `scheduleMaxTotalPerUser` schedules across every database (`400-33`).
- **Not transactional.** A trigger runs inside a transaction because its pending-run record must be consumed atomically with its effects; a schedule has no run record, so a scheduled procedure that wants atomicity opens its own `db.transaction(...)` — which, unlike inside a trigger, is permitted.
- `args` is the object handed to the procedure as its `args` module, exactly as with [`CALL_PROCEDURE`](#call_procedure). Because a schedule is a separate record from the procedure, one procedure can carry several schedules with different arguments.
- `timeoutMs` bounds one run's wall clock; omit it (or `0`) to use `scheduleTimeoutMs`. It may not exceed `scheduleMaxTimeoutMs` (default `300000`); a larger value is refused with `400-29`. Everything else — instruction budget, depth, memory, log caps — comes from the `script*` keys.
- `enabled` defaults to `true`. `ifVersion` is optional optimistic concurrency: present and not equal to the stored version → `409-8`. Use `0` to require that the schedule does not exist yet.
- **Delivery is at-most-once per due instant.** Firing is never finer-grained than `scheduleTickMs`, a run still executing when the next occurrence is due is skipped rather than queued twice, and **missed runs while a node was down are skipped, not caught up** — a job that must not miss an occurrence should be idempotent and driven off data, not off the clock. Under clustering each schedule is owned by exactly one node through the consistent-hash ring, so a schedule fires once per due instant across the cluster and fails over automatically; a membership change during a tick may drop that tick rather than run it twice (see [docs/clustering.md](clustering.md) → *Scheduled procedures*).
- A schedule is stored **with its database**, in `{filePath}/{database}/.schedules/{name}.json`, so dropping the database removes it.
- **Errors**: `403-2` schedules disabled, `403-1` not permitted, `403-5` replacing another user's schedule, `404-4` unknown database, `404-8` unknown procedure, `400-1` an invalid name (3–64 alphanumerics plus `_` and `-`), `400-16` neither or both of `cron`/`intervalMs`, a malformed cron, or a negative `timeoutMs`, `400-17` past `scheduleMaxPerDatabase`, `400-29` a `timeoutMs` past `scheduleMaxTimeoutMs`, `400-33` past `scheduleMaxTotalPerUser`, `400-35` an `intervalMs` (or a `cron` that fires) more often than `scheduleMinIntervalMs` allows, `409-8` version conflict.
- **A full schedule queue never drops another database's run.** When `scheduleQueueSize` is reached, the oldest queued run of the same database is dropped to make room; if that database has nothing queued, the new run is the one dropped. Triggers follow the same rule on `triggerQueueSize` and `triggerQueueMaxBytes`.

### `DELETE_SCHEDULE`
```json
{"type":"DELETE_SCHEDULE","databaseName":"mydb","name":"nightlyRollup"}
```
Idempotent — succeeds whether or not the schedule existed. A schedule installed by another user can only be deleted by its definer, a database owner or an admin (`403-5`). A [`DELETE_PROCEDURE`](#delete_procedure) is refused with `400-16` while a schedule still references it (the message names the schedule).

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
`args` is omitted from a listing unless the request sets `"includeArgs":true`, which returns each schedule's full
definition including `args`. Like `LIST_PROCEDURES`' `includeSource`, `includeArgs` also requires script `MANAGE` on
the database (or ownership, or admin); a caller with only `READ`/`RUN` is refused with `403-6`. `nextRunAt` and `owner` are computed by the answering node — the schedule registry is in-memory and per-node, so both describe that node's view rather than a cluster-wide fact (`owner` is absent when clustering is off). Requires `READ` on the database.

### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

## Schema validation

A collection can carry a single **JSON Schema (draft 2020-12)** that every write must satisfy. Attach one with [`SAVE_SCHEMA`](#save_schema) and remove it with [`DELETE_SCHEMA`](#delete_schema) (both require admin privileges or database ownership). While a schema is in force, every `SAVE` and `BULK_SAVE` document is validated **before it is committed**, so a non-compliant document never reaches the collection:

- A non-compliant `SAVE` is rejected with `400-7` and the document is not written.
- A `BULK_SAVE` is **all-or-nothing**: if any document violates the schema the whole batch is rejected with `400-7` (the message names the offending `_id`), and nothing is written.
- Collections without a schema are unconstrained (existing behaviour is unchanged).
- Validation is **bounded**. Each write request gets an evaluation budget of `schemaValidationBudget` steps (default 5 000 000), shared by every document of a `BULK_SAVE`: one step per schema node evaluated against a value, plus one per step of the regex matcher running a `pattern`, `patternProperties` or `propertyNames` regex (a regex with many capture groups costs more per start position, one extra step per 32 groups, and comparing a literal or a backreference costs one extra step per 64 characters compared). A document that exhausts it is refused with `400-23` and nothing is written, so a catastrophic pattern such as `(x+x+)+y` or an exponential `$ref` diamond cannot hold the collection write lock indefinitely. In a `BULK_SAVE` the whole batch is refused, inside a transaction the write answers `400-23` and the transaction stays open, and a before trigger whose replacement document exhausts the budget is rejected with `400-21`. The budget is a step count, not a deadline, so nodes configured with the same `schemaValidationBudget` accept and refuse the same document (keep it equal on every node of a cluster; the owner's check under the collection lock is the one that decides); legitimate 1 Mb documents cost a few hundred thousand steps at most. Cheap documents against a schema with a dangerous pattern still pass: only the documents that actually blow the budget are refused.

The reserved `_id` field is **excluded** from validation (it is a system-assigned primary key, already format-checked), so a schema with `"additionalProperties": false` does not need to declare it.

**Supported keywords** (a pragmatic subset of 2020-12): `type` (incl. `integer` vs `number`), `enum`, `const`; object — `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `minProperties`/`maxProperties`, `dependentRequired`, `dependentSchemas`; array — `prefixItems`, `items`, `minItems`/`maxItems`, `uniqueItems`, `contains`/`minContains`/`maxContains`; string — `minLength`/`maxLength`/`pattern` (`pattern`, `patternProperties` keys and `propertyNames` patterns use ECMA-262 syntax in Unicode mode, as 2020-12 specifies — the same engine as SimpleJS's `RegExp` with the `u` flag, so `$` does not match before a trailing newline and `\s` includes Unicode whitespace; Java-only syntax such as `a++` or `(?i)` is refused with `400-8`, and a schema stored with such a pattern before this rule refuses writes with `503-11` until it is re-saved); number — `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`; applicators — `allOf`/`anyOf`/`oneOf`/`not`/`if`/`then`/`else`; and local `$ref` (JSON-Pointer references within the same schema, e.g. `#/$defs/foo`) with `$defs`; a `$ref` target is validated as a schema wherever it lives, so one pointing into an unrecognized keyword such as draft-07 `definitions` is checked rather than trusted. `format` is accepted but **non-asserting** (annotation only). Metadata keywords (`$schema`, `$id`, `title`, `description`, …) are accepted; any **unrecognized** keyword is ignored but surfaced as a non-fatal `warning` on the `SAVE_SCHEMA` response. Known-but-unimplemented keywords (`unevaluatedProperties`/`unevaluatedItems`, `$dynamicRef`, remote `$ref`, `$vocabulary`) are **rejected** by `SAVE_SCHEMA` (`400-8`) so a schema author is never misled into believing an unenforced constraint applies.

**Custom (EJson) types.** Beyond the standard `type`, a dedicated `customType` keyword asserts one of the extended types (`geo`, `vector`, `datetime`, `time`), e.g. `{"customType":"geo"}` requires a `#geo(lat,lng)` value. Custom-typed values also satisfy `"type":"string"` (they are stored as strings), but only `customType` distinguishes a geo from an arbitrary string.

Schemas are **user data**: each is stored as `{collection}-schema.json` in the collection's folder and cached in memory so validation adds negligible per-write cost. Their compiled regular expressions are cached node-wide, weighed by estimated heap against `schemaPatternCacheMaxBytes` (approximately least recently used first out; a cache hit takes no lock, so validation with patterns scales across connections). Under clustering they are replicated to every node (the schema op is coordinator-serialized DDL, and the coordinator ships the resulting versioned schema record to its peers) and reconciled by admin anti-entropy, so a node that was down during a `SAVE_SCHEMA`/`DELETE_SCHEMA` catches up on rejoin; a deleted schema leaves a permanent tombstone, so it is never brought back.

## Users & Permissions

Every connection must authenticate before sending any protected operation. `AUTHENTICATE` and `CLOSE_CONNECTION` are the only operations that do not require authentication (`LIST_DATABASES` used to be public; it now answers `401-1` before authenticating).

### `AUTHENTICATE`
```json
{"type":"AUTHENTICATE","username":"Alice","password":"secret"}
```

Failed attempts are throttled, never locked out:

- **Delay per address and username.** After the k-th consecutive failure for a username from one remote address,
  the next attempt for that username from that address waits `authFailureDelayBaseMs * 2^(k-1)` milliseconds
  (250ms, 500ms, 1s, 2s ... capped at `authFailureDelayMaxMs`, 30s by default) before the password is checked. A
  success clears it. Other addresses, and other usernames from the same address, are not delayed.
- **Cap per connection.** The `maxFailedAuthAttempts`-th failure on one connection (5 by default) answers `401-4`
  and the server closes the connection. The count covers every failure on that connection and is not reset by a
  successful login. `0` disables the cap.
- **Unknown users cost the same.** An unknown username is checked against a dummy hash of the same strength and
  answers the same `401-3`, so response time does not reveal which usernames exist.
- **Bounded hashing.** At most `maxConcurrentPasswordChecks` password hashes (`AUTHENTICATE` and `SET_PASSWORD`)
  run at once (default: one per available processor), on their own threads, so a burst of logins does not slow
  requests on connections that are already authenticated; the rest queue.
- **Repeated logins skip the hash.** With `authVerifierCacheEnabled=true` (the default) a successful
  `AUTHENTICATE` is remembered as a keyed MAC of the user's stored hash and the password, under a key that lives
  only in the server's memory, so the next `AUTHENTICATE` with the same password answers without the hash. A wrong
  password, a changed password, a deleted user or a server restart all fall back to the full hash. The first login
  of each user after a start still costs a full hash (about 10 ms of CPU), so clients that open many short
  connections should keep a connection pool.

### `CREATE_USER` (admin only)
`globalPermissions`, `databasePermissions`, `collectionPermissions`, and `scriptPermissions` are all optional (default to empty). Collection permission keys must be in `database|collection` format; `scriptPermissions` keys are database names and its values are the levels `NONE`, `RUN` or `MANAGE` (the boolean form older clients send is still accepted — `true` reads as `RUN`). See [Permission model](#permission-model).
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

### `DELETE_USER` (admin only)
```json
{"type":"DELETE_USER","username":"bob"}
```

### `CHANGE_PERMISSIONS` (admin only)
Replaces all permissions for the user in full.
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
A user can change their own password by providing `currentPassword` for verification. An admin can change any user's password without supplying `currentPassword`. Non-admins cannot change another user's password: such a request answers `403-1` whether or not the target user exists, so it cannot be used to discover usernames.

A successful change signs the user out of every other connection: their next request answers `401-1` until they
authenticate with the new password. The connection that made the change stays authenticated only when it changed
its own password; when an admin changes someone else's password, every connection of that user is signed out. A
connection with an open transaction can still roll it back. In a cluster the other nodes do the same when the
replicated user record arrives with a different password hash, so a client that changed its own password through a
node other than the coordinator also has to authenticate again on that node.

| Field | Required | Notes |
|---|---|---|
| `username` | yes | Target user |
| `newPassword` | yes | Minimum 8 characters |
| `currentPassword` | for non-admins changing own password | Not required when an admin changes another user's password |

```json
{"type":"SET_PASSWORD","username":"Alice","currentPassword":"old_pass","newPassword":"new_pass_1234"}
```

Admin changing another user's password (no `currentPassword` needed):
```json
{"type":"SET_PASSWORD","username":"Alice","newPassword":"new_pass_1234"}
```

### `SET_DATABASE_OWNERS` (admin only)
Replaces the full owners list for a database. All usernames must already exist. The creator of a database is automatically set as its first owner.
```json
{"type":"SET_DATABASE_OWNERS","databaseName":"my_db","owners":["Alice","bob"]}
```

### `GET_DATABASE_STATS`
Returns memory usage, totals, and per-database/per-collection breakdown. Useful for monitoring eviction and tuning `maxMemory`. `memory.userCacheBytes` is the modelled heap of the user cache (the total `maxMemory` is enforced against); the `heap*` fields are the JVM's measured heap. `memory.residentTombstoneBytes` is the modelled heap of the delete tombstones a clustered node keeps in memory for `tombstoneRetentionMs` (outside `maxMemory`, about 12 bytes per tombstone plus its id).

```json
{"type":"GET_DATABASE_STATS"}
```

Response shape:

```json
{
  "type": "GET_DATABASE_STATS",
  "status": "OK",
  "stats": {
    "memory": {
      "heapUsedBytes": 123456789,
      "heapMaxBytes": 6442450944,
      "heapCommittedBytes": 268435456,
      "userCacheBytes": 2097152,
      "residentTombstoneBytes": 0,
      "maxMemoryBytes": 536870912,
      "cachingDisabled": false,
      "cacheUnlimited": false
    },
    "triggers": {
      "enabled": true,
      "fired": 128,
      "failed": 0,
      "dropped": 0,
      "queued": 0
    },
    "scripts": {
      "routingEnabled": true,
      "running": 2,
      "capacity": 16,
      "available": 14,
      "rejected": 0,
      "waited": 7,
      "forwarded": 118,
      "forwardFallbacks": 3,
      "outcomeUnknown": 0,
      "localityWeight": 50,
      "localityPreferred": 47,
      "cancelled": 1
    },
    "totals": {
      "userCount": 3,
      "databaseCount": 1,
      "collectionCount": 2,
      "indexCount": 1,
      "pageCount": 4,
      "entryCount": 5000,
      "sizeBytes": 2560000
    },
    "databases": [
      {
        "name": "my_db",
        "collectionCount": 2,
        "indexCount": 1,
        "pageCount": 4,
        "entryCount": 5000,
        "sizeBytes": 2560000,
        "collections": [
          {
            "name": "my_coll",
            "indexCount": 1,
            "indexes": ["email"],
            "pageCount": 2,
            "entryCount": 3000,
            "sizeBytes": 1536000
          }
        ]
      }
    ]
  }
}
```

Any authenticated user may send it, and the answer is filtered by what that user can see. An admin gets the full report
above. Anyone else gets only:
- the databases [`LIST_DATABASES`](#list_databases) would show them;
- inside each, the collections they own or hold `READ` on (an owner sees every collection; a user with only a script
  grant sees the database with no collections);
- `totals` summed over that view, without `userCount`.

The node-wide sections — `memory`, `inDoubtTransactions`, `triggers`, `schedules`, `scripts` — are admin-only, since
they describe the whole node and every tenant on it.

```json
{"type":"GET_DATABASE_STATS","status":"OK","message":"Ok","stats":{
  "totals":{"databaseCount":1,"collectionCount":1,"indexCount":1,"pageCount":2,"entryCount":2495,"sizeBytes":800000},
  "databases":[{"name":"shop","collectionCount":1,"indexCount":1,"pageCount":2,"entryCount":2495,"sizeBytes":800000,
    "collections":[{"name":"orders","indexCount":1,"indexes":["customer"],"pageCount":2,"entryCount":2495,"sizeBytes":800000}]}]}}
```

### Snapshots (`START_SNAPSHOT`, `READ_SNAPSHOT`, `RELEASE_SNAPSHOT`)

What [`--dump`](../README.md#client-shell-exec-dumprestore) reads a database through. They are wire operations any client may
send, but they are not part of the [client libraries](../README.md#client-libraries). `START_SNAPSHOT` needs `READ` on the database
and captures the collections the user may read (all of them, or those named in `collectionNames`). It holds the read
locks of all of them at once for as long as registering takes, so the cut falls between two transactions; it waits at
most `transactionLockTimeoutMs` for them and answers `409-5` past it, or `503-18` (retryable) at once when one of
them is held by a committed transaction that has not finished applying. From then on, the first write that replaces or
deletes a document the snapshot holds first copies its old version aside, so the snapshot keeps reading it.
```json
{"type":"START_SNAPSHOT","databaseName":"shop","collectionNames":["orders","customers"]}
{"type":"START_SNAPSHOT","status":"OK","message":"Ok","errorCode":null,"snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10","collectionNames":["customers","orders"]}
```
`READ_SNAPSHOT` pages one collection in `_id` order, `limit` (1 to 100000) ids at a time after `afterId` (absent for the
first page). A page can hold fewer documents than `limit` while `done` is `false`, because the ids inserted after the
cut are skipped; the next page starts after `lastId`.
```json
{"type":"READ_SNAPSHOT","databaseName":"shop","collectionName":"orders","snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10","afterId":null,"limit":1000}
{"type":"READ_SNAPSHOT","status":"OK","message":"Ok","errorCode":null,"results":[{"_id":"o-1","total":12}],"lastId":"o-1","done":false}
{"type":"RELEASE_SNAPSHOT","databaseName":"shop","snapshotId":"6f1c0d2e-4b7a-4c1e-9f3a-2d5e8b7c6a10"}
```
A snapshot belongs to the connection that opened it: another connection's id answers `404-13`, and closing the
connection releases it, as does leaving it unread for `snapshotIdleTimeoutMs` (default `300000`) or a shutdown. At most
`snapshotMaxOpen` (default `4`) are open on a node at once; one more answers `503-22`. A snapshot whose old versions pass
`snapshotMaxBytes` (default `1Gb`), or one of whose collections is dropped or has its primary-key index rebuilt, is
aborted: its reads answer `409-17` with the reason until it is released, and the writes that passed it are never
affected. Snapshots are not forced to disk and do not survive a restart; their old versions are kept under
`{filePath}/.snapshots` and deleted on release and at startup. On a cluster a snapshot is local to the node the client
is connected to; it keeps the versions that writes replicated to that node replace.

### `SERVER_INFO`

Says which build a client is talking to and whether the node is ready. It is the one operation besides
`AUTHENTICATE` and `CLOSE_CONNECTION` that is answered **before authentication**, and it is still answered while the
server shuts down, so a load balancer or a container health check can use it without a password. It takes no fields.

```json
{"type":"SERVER_INFO"}
```

What comes back depends on who asks:

- **Anyone**, authenticated or not, gets `state` alone, so an anonymous connection learns nothing that identifies the
  build:
  ```json
  {"type":"SERVER_INFO","status":"OK","message":"Ok","server":{"state":"READY"}}
  ```
- **Any authenticated user** also gets `version` (the release, or `dev` for a server not run from a packaged jar),
  `protocolVersion` (raised whenever a wire field changes; `1` today), `storageFormatVersion` (raised whenever a file
  under `filePath` changes shape; `1` today, see [docs/compatibility.md](compatibility.md)), `startedAtMs` (epoch
  milliseconds) and `uptimeMs`.
- **An admin** also gets `node`: `clusterEnabled`, `syncWrites` and `tlsEnabled`, plus `nodeId`, `aliveMembers` (this
  node included) and `expectedSize` when clustering is on.
  ```json
  {"type":"SERVER_INFO","status":"OK","message":"Ok","server":{"state":"READY","version":"0.1.0",
    "protocolVersion":1,"storageFormatVersion":1,"startedAtMs":1762790400000,"uptimeMs":5231,
    "node":{"clusterEnabled":true,"nodeId":"n1","aliveMembers":3,"expectedSize":3,"syncWrites":true,"tlsEnabled":false}}}
  ```

| `state` | Meaning |
|---|---|
| `READY` | Serving requests |
| `SYNCING` | A cluster node that has not finished its first admin sync; admin changes answer `503-5` until it has |
| `SHUTTING_DOWN` | The server is stopping; every other request answers `503-13` |

`SERVER_INFO` always describes the node it was sent to: it is never forwarded to another node, it is allowed inside a
transaction, and it is never refused for a full background queue or the response budget. The server also logs
`LWNRDB <version> starting` as its first line. **Errors**: `500-35` on an internal failure.

### `LIST_USERS` (admin only)
Returns all users with their permissions. `passwordHash` is never included in the response. `aggregationSteps` is optional; when omitted all users are returned.

Each user object in the response contains:

| Field | Type | Description |
|---|---|---|
| `_id` | string | Username |
| `admin` | boolean | Whether the user is a superadmin |
| `globalPermissions` | array | e.g. `["CREATE_DATABASE"]` |
| `databasePermissions` | object | e.g. `{"mydb": "READ_WRITE"}` |
| `collectionPermissions` | object | e.g. `{"mydb&#124;coll": "READ"}` |
| `scriptPermissions` | object | Per-database script level, e.g. `{"mydb": "MANAGE"}` |
| `ownedDatabases` | array | Databases where this user is an owner |

```json
{"type":"LIST_USERS"}
```

Supports the same `aggregationSteps` as `AGGREGATE` for filtering, sorting, counting, etc.:
```json
{
  "type": "LIST_USERS",
  "aggregationSteps": [
    {"type":"FILTER","operator":{"fieldOperatorType":"EQUALS","field":"admin","value":true}},
    {"type":"SORT","fieldName":"_id","ascending":true}
  ]
}
```

Example filters:

| Goal | Step |
|---|---|
| Find user by username | `FILTER` with `_id EQUALS "Alice"` |
| Find all admins | `FILTER` with `admin EQUALS true` |
| Find owners of a database | `FILTER` with `ownedDatabases CONTAINS "mydb"` |
| Count users | `COUNT` |

## Permission model

| Concept                 | Description                                                                           |
|-------------------------|---------------------------------------------------------------------------------------|
| `admin` flag            | Superadmin — bypasses all permission checks                                           |
| Database ownership      | Full access to the database and all its collections, including the ability to drop it |
| `globalPermissions`     | `CREATE_DATABASE` — required to create new databases                                  |
| `scriptPermissions`     | Per database: `NONE` / `RUN` / `MANAGE` — see below                                   |
| `databasePermissions`   | Grants `READ` or `READ_WRITE` to all collections in a database                        |
| `collectionPermissions` | Grants `READ` or `READ_WRITE` to a specific `database\|collection`                    |

Ownership takes precedence over `databasePermissions` and `collectionPermissions`. A `collectionPermissions` entry is authoritative for the collection it names: it replaces the database-level grant for that collection, so it can widen it *or* narrow it — a database `READ_WRITE` grant next to a `db|coll` entry of `READ` leaves that one collection read-only. A collection with no entry of its own falls back to `databasePermissions`. `READ_WRITE` also covers `READ`. Because [`CREATE_COLLECTION`](#create_collection) needs `READ_WRITE` on the collection it names, a `READ` entry for a name that does not exist yet also stops that user creating it. There is no collection-level way to deny reads outright: the only levels are `READ` and `READ_WRITE`.

> **Upgrade note.** Earlier versions unioned the two levels, so a `collectionPermissions` entry could only ever widen a `databasePermissions` grant and a carve-out silently had no effect. A user who holds both a database `READ_WRITE` grant and a `READ` entry for one of its collections loses write access to that collection on upgrade — which is the behaviour documented above finally taking effect. Check existing users with [`LIST_USERS`](#list_users-admin-only) before rolling out if you are unsure whether any grant pairs that way.

`DROP_DATABASE` requires admin privileges or ownership — the `globalPermissions` field no longer grants the ability to drop databases.

`scriptPermissions` is a **per-database level**, `{"mydb": "MANAGE"}`:

| Level | Allows |
|---|---|
| `NONE` (or absent) | nothing |
| `RUN` | [`RUN_SCRIPT`](#run_script) and [`CALL_PROCEDURE`](#call_procedure) |
| `MANAGE` | everything `RUN` allows, plus installing procedures, triggers and schedules ([`SAVE_PROCEDURE`](#save_procedure), [`DELETE_PROCEDURE`](#delete_procedure), [`SAVE_TRIGGER`](#save_trigger), [`DELETE_TRIGGER`](#delete_trigger), [`TEST_TRIGGER`](#test_trigger), [`SAVE_SCHEDULE`](#save_schedule), [`DELETE_SCHEDULE`](#delete_schedule)). The three trigger operations also need `READ_WRITE` on the collection (its entry, else the database grant); changing a procedure another user's trigger, before hook or schedule runs is refused with `403-4` |

Admins and database owners have an implicit `MANAGE` on the databases they reach. A grant on one database says nothing about another. The boolean form written by older clients (`{"mydb": true}`) is still accepted and reads as `RUN`; `false` reads as `NONE`. A value that is neither a boolean nor a level name is rejected with `400-1` rather than read as a denial.

Installing is deliberately its own level rather than something a `READ_WRITE` grant confers. A procedure called through `CALL_PROCEDURE` runs with the **caller's** authority, so whoever installs one hands every higher-privileged caller code to execute — and a **trigger** or a **schedule** runs with the *installer's* authority, which makes installing strictly more powerful than writing.

Being allowed to start a script is separate from what it may do: every operation a script issues is authorized again on its own request against `databasePermissions`/`collectionPermissions`, so a user granted `RUN` plus `READ` can run a script that reads but not one that writes. The one exception is a trigger, which runs as its `definer` — see [`SAVE_TRIGGER`](#save_trigger).

> **Upgrade note.** Roll every node to this version before granting `MANAGE` or otherwise rewriting a user record in a cluster. User records replicate by shipping the record itself, and a node without `scriptPermissions` levels cannot parse the string form — it would skip the whole user record, not just the grant. Note that any write to a user record converts it (a password change will do), so the ordering matters even if you never touch a script grant.

Operations that require `READ`: `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `GET_SCHEMA`, `LISTEN`, `LIST_PROCEDURES`, `LIST_TRIGGERS`, `LIST_SCHEDULES`. A `LISTEN` or `AGGREGATE` that contains a `JOIN` step additionally requires `READ` on each joined collection (in the same database); otherwise the request is rejected with `FORBIDDEN`. An `AGGREGATE` whose pipeline carries a [script operator](#script-operators-simplejs-in-the-pipeline) additionally requires `RUN`, since running code is wider than reading.  
Operations that require `READ_WRITE`: `SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`, `SAVE_SCHEMA`, `DELETE_SCHEMA` (the last two also being available to database owners and admins, like the other DDL operations).
`LIST_DATABASES` and [`GET_DATABASE_STATS`](#get_database_stats) need no grant either: any authenticated user may send them, and each answers only what that user can see.  
`START_TRANSACTION`, `COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` need no grant: any authenticated user may use a transaction, and every operation inside it is authorized on its own request, so a `READ` user can open one but cannot buffer a write.

## Authentication errors

| Situation | `status` | `errorCode` | `message` |
|---|---|---|---|
| Request sent before authenticating | `UNAUTHENTICATED` | `401-1` | `Must authenticate first` |
| Authenticated user was deleted mid-session | `UNAUTHENTICATED` | `401-2` | `User no longer exists` |
| Wrong username or password | `ERROR` | `401-3` | `The user doesn't exist or the wrong credentials have been provided` |
| Too many failed `AUTHENTICATE` attempts on one connection (the server then closes it) | `UNAUTHENTICATED` | `401-4` | `Too many failed authentication attempts on this connection` |
| Insufficient permissions | `FORBIDDEN` | `403-1` | `Action is forbidden, no permissions` |

## Fault injection (testing and debugging only)

These three operations are **not for applications**. They exist so the cluster test suite can make one node fail at an exact statement inside the engine — freeze there, crash there, or drop one reply to a peer — which is how it reproduces failures that only happen *between* two adjacent steps. They are inert unless the server was started with `faultInjectionEnabled=true` (default `false`); otherwise each one answers `403-3`. All three are admin only, act on the node the client is connected to (they are never forwarded), and are refused inside an open transaction.

A fault is armed at a named **point**. It fires once, the first time a thread reaches the point with a matching subject, and then stays inert until it is armed again.

| Point | Where it fires | Subject (`match`) |
|---|---|---|
| `WRITE_OWNERSHIP_RECHECKED` | A clustered write re-checked, under its collection lock, that this node owns the collection, and has not applied yet | `<db>\|<collection>` |
| `WRITE_OWNER_PROBED` | Right after `WRITE_OWNERSHIP_RECHECKED`, at the same statement; a separate point so a test can find a collection's owner without releasing a write parked on `WRITE_OWNERSHIP_RECHECKED` | `<db>\|<collection>` |
| `DELETE_TOMBSTONE_RESERVED` | A clustered delete wrote its tombstone and has not deleted the document yet | `<db>\|<collection>` |
| `ANTI_ENTROPY_SNAPSHOTTED` | An anti-entropy round took its snapshot and released its lock, and has not sent its digest yet | `<db>\|<collection>` |
| `REPLICATED_APPLY_LOCKED` | A replica took the collection write lock for a peer's replicated write and has not applied it yet | `<db>\|<collection>` |
| `REPLICATED_TOMBSTONES_APPENDED` | A replica appended a replicated delete batch's missing tombstones, unforced, and has not deleted the documents yet | `<db>\|<collection>` |
| `PK_INDEX_REPLACED` | A `REINDEX` with `"primaryKey": true` wrote the rebuilt `pk.idx` and has not rebuilt the page rows or field indexes yet | `<db>\|<collection>` |
| `INDEX_BATCH_FLUSHED` | A background field-index batch appended to the first of the type logs it changed and has not appended to the others yet | `<db>\|<collection>` |
| `PAGE_COPY_APPENDED` | An update, relocation or bulk update logged its record in the write-ahead log and has not written its copies, `pk.idx` rows or blanks yet | `<db>\|<collection>` |
| `PK_ROW_COMMITTED` | An update, relocation, bulk update or delete wrote everything its logged record describes: the new copies, the `pk.idx` rows and the blanks of the copies they supersede | `<db>\|<collection>` |
| `WAL_REWRITE_BEGUN` | A page vacuum logged its forced `REWRITE_BEGIN` frame and has not written its intent, rewritten or truncated the page yet | `<db>\|<collection>` |
| `PAGE_VACUUM_RENAMED` | A page vacuum moved its rewritten page into place and has not appended the moved records' rows to `pk.idx` yet | `<db>\|<collection>` |
| `WAL_APPENDED` | Any write appended its record to the collection's write-ahead log and has not written the pages or `pk.idx` yet; with `syncWrites` an ungrouped write has forced the record by then, while a write inside a write group (a grouped `SAVE`/`DELETE`, or a transaction commit's group) appends it unforced and the group forces it later | `<db>\|<collection>` |
| `WAL_CHECKPOINT_FORCED` | A checkpoint forced the pages and `pk.idx` the log touched and has not rewritten the log as a single checkpoint yet | `<db>\|<collection>` |
| `WAL_GROUP_APPENDED` | A write group appended its records to the log, unforced, and has not forced it yet | `<db>\|<collection>` |
| `WAL_GROUP_FORCED` | A write group forced its records and has not applied the blanks they wait for yet | `<db>\|<collection>` |
| `GROUP_RUNS_STAGED` | A write group recorded the after-trigger runs of all its writes in one forced record and has not appended any write yet | `<db>\|<collection>` |
| `PEER_REPLY` | A node applied a peer's request and has not written the reply yet. The only point that can `DROP` | the request type, e.g. `COMMIT_TX` |
| `FILE_FORCED` | Any file or folder is about to be forced to the device. The only point that can `FAIL`: the force then throws `injected force failure` instead of syncing, as a failed `fsync` would | the file name, e.g. `orders.wal`, or a folder name followed by `/`, e.g. `orders/` |

| Action | Effect |
|---|---|
| `PAUSE` | Parks the thread until `RELEASE_FAULT`, for at most 120 seconds |
| `HALT` | Stops the process at that statement with exit status `137`, skipping shutdown hooks, like `kill -9` |
| `DROP` | Skips writing the reply (`PEER_REPLY` only) |
| `FAIL` | Fails the force with an `IOException` (`FILE_FORCED` only). With `powerLossShadowPath` set, the file is also frozen: no later force of it counts as durable, which models a kernel that drops the dirty pages of a failed `fsync` |

### `ARM_FAULT` (admin only, testing only)
`match` is optional; without it any subject fires the fault. Arming a point that is already armed releases the previous arm first.
```json
{"type":"ARM_FAULT","point":"PEER_REPLY","action":"DROP","match":"COMMIT_TX"}
{"type":"ARM_FAULT","point":"FILE_FORCED","action":"FAIL","match":"orders.wal"}
```

### `AWAIT_FAULT` (admin only, testing only)
Waits until the armed point has fired, up to `timeoutMs` (1–120000). Answers `408-3` if it did not, or if the point is not armed.
```json
{"type":"AWAIT_FAULT","point":"WRITE_OWNERSHIP_RECHECKED","timeoutMs":30000}
```

### `RELEASE_FAULT` (admin only, testing only)
Lets a paused thread continue and disarms the point. Without `point` it does so for every point.
```json
{"type":"RELEASE_FAULT","point":"WRITE_OWNERSHIP_RECHECKED"}
```

- **Errors**: `403-3` fault injection is disabled, `403-1` not an admin, `400-1` an unknown point or action, `DROP` on a point other than `PEER_REPLY`, `FAIL` on a point other than `FILE_FORCED`, or a `timeoutMs` out of range, `408-3` the point was not reached in time.

## Error codes

Every error response includes an `errorCode` field. Codes follow the pattern `NNN-N` (HTTP-style range + sequential number). The `message` field may contain additional context (e.g. the offending id or field name) appended to the default text below.

| Code | `status` | Retryable | Default message |
|---|---|---|---|
| `400-1` | `ERROR` |  | *(validation message from the request validator)* |
| `400-2` | `ERROR` |  | Entry size exceeds maximum allowed size |
| `400-3` | `ERROR` |  | Duplicate `_id` in bulk save request |
| `400-4` | `ERROR` |  | Cannot delete the last admin user |
| `400-5` | `ERROR` |  | Cannot demote the last admin user |
| `400-6` | `ERROR` |  | Current password is incorrect |
| `400-7` | `ERROR` |  | Document does not comply with the collection schema |
| `400-8` | `ERROR` |  | The provided JSON schema is not valid |
| `400-9` | `ERROR` |  | Script execution failed |
| `400-10` | `ERROR` |  | Script exceeds the maximum allowed size |
| `400-11` | `ERROR` |  | Script exceeded a sandbox limit |
| `400-12` | `ERROR` |  | Script exceeded its memory budget |
| `400-13` | `ERROR` |  | The procedure source could not be parsed |
| `400-14` | `ERROR` |  | The trigger definition is not valid |
| `400-15` | `ERROR` |  | Script result exceeds the maximum allowed size |
| `400-16` | `ERROR` |  | The schedule definition is not valid |
| `400-17` | `ERROR` |  | The database already has the maximum number of schedules |
| `400-18` | `ERROR` |  | A saved procedure imports a `procedures/<name>` that does not exist or is disabled |
| `400-19` | `ERROR` |  | A `SCRIPT` operator is not allowed in a `LISTEN` pipeline |
| `400-20` | `ERROR` |  | The script's result promise never settled |
| `400-21` | `ERROR` |  | A before trigger rejected this write |
| `400-22` | `ERROR` |  | The request line exceeds the size limit (`maxRequestSize`, or 64Kb before authentication); sent as a `CLOSE_CONNECTION` frame and the connection is closed |
| `400-23` | `ERROR` |  | Schema validation exceeded its evaluation budget |
| `400-24` | `ERROR` |  | The schema expands past the complexity limit |
| `400-25` | `ERROR` |  | Too many listen registrations — the connection already holds `maxListensPerConnection`, or the server `maxListens` |
| `400-26` | `ERROR` |  | The transaction would exceed its size limit (`transactionMaxOps` or `transactionMaxBytes`); the write is refused and the transaction stays usable for `ROLLBACK_TRANSACTION` |
| `400-27` | `ERROR` |  | The filter has more operands than allowed — an `IN`/`NOT_IN` list, a `within` polygon, one conjunction's operands, one `FILTER` step's operator nodes or one `MAP` step's operators and operands past `maxFilterOperands` |
| `400-28` | `ERROR` |  | A MAP result exceeds the maximum entry size — a `CONCAT` built a string longer than `maxEntrySize`, one `MAP` step added more than `maxEntrySize` bytes of strings or `SCRIPT` values to a document, or one `JOIN` row's joined array is larger than `maxEntrySize` |
| `400-29` | `ERROR` |  | The schedule timeout exceeds the allowed maximum (`scheduleMaxTimeoutMs`) |
| `400-30` | `ERROR` |  | A script array exceeds the maximum length — an array-like `length` past `scriptMaxArrayLength` reached `apply`, `Reflect.apply`/`construct`, `Vector.from` or `Array.fromAsync` |
| `400-31` | `ERROR` |  | A schema regular expression exceeds the maximum length — a `pattern` or `patternProperties` key longer than `schemaMaxPatternLength`, refused by `SAVE_SCHEMA` |
| `400-32` | `ERROR` |  | The collection has the maximum number of triggers (`triggerMaxPerCollection`); replacing an existing trigger is never refused |
| `400-33` | `ERROR` |  | The user has the maximum number of schedules across every database (`scheduleMaxTotalPerUser`) |
| `400-34` | `ERROR` |  | The database has the maximum number of procedures (`maxProceduresPerDatabase`); replacing an existing procedure is never refused |
| `400-35` | `ERROR` |  | The schedule interval is below the allowed minimum (`scheduleMinIntervalMs`) |
| `400-36` | `ERROR` |  | The aggregation result exceeds the maximum result size — the `MAP` strings and `SCRIPT` values, `JOIN` arrays, the documents its steps held (`GROUP_BY`, unbounded `SORT`, `JOIN`, `DISTINCT`) and the result rows one pipeline built passed `aggregateMaxResultBytes` |
| `400-37` | `ERROR` |  | The `LISTEN` request line exceeds `listenMaxRequestBytes`; refused before the query runs |
| `400-38` | `ERROR` |  | The user already holds `maxListensPerUser` listen registrations across their connections |
| `400-39` | `ERROR` |  | The schema has more regular expressions than allowed — more `pattern` values and `patternProperties` keys than `schemaMaxPatterns`, refused by `SAVE_SCHEMA` before compiling any |
| `400-40` | `ERROR` |  | The database has the maximum number of collections (`maxCollectionsPerDatabase`); re-creating an existing collection is never refused |
| `400-41` | `ERROR` |  | The server has the maximum number of databases (`maxDatabases`) |
| `400-42` | `ERROR` |  | The collection has the maximum number of indexes (`maxIndexesPerCollection`); re-creating an existing index is never refused |
| `400-43` | `ERROR` |  | The command is not valid: the line is not a JSON object, names no known `type`, is denser than the token budget, nests deeper than 128 levels, holds a custom value past its limits (such as a `#vector` over `maxVectorDimensions`), or has a field of the wrong shape. `type` is the request's own when it could be read, `null` otherwise; the connection stays open |
| `401-1` | `UNAUTHENTICATED` |  | Must authenticate first |
| `401-2` | `UNAUTHENTICATED` |  | User no longer exists |
| `401-3` | `ERROR` |  | The user doesn't exist or the wrong credentials have been provided |
| `401-4` | `UNAUTHENTICATED` |  | Too many failed authentication attempts on this connection — the server closes the connection after sending it |
| `403-1` | `FORBIDDEN` |  | Action is forbidden, no permissions |
| `403-2` | `FORBIDDEN` |  | Script execution is disabled on this server |
| `403-3` | `FORBIDDEN` |  | Fault injection is disabled on this server — a [fault injection](#fault-injection-testing-and-debugging-only) operation on a server without `faultInjectionEnabled=true` |
| `403-4` | `FORBIDDEN` |  | Procedure is bound by a trigger or schedule that runs as another user — a [`SAVE_PROCEDURE`](#save_procedure) or [`DELETE_PROCEDURE`](#delete_procedure) by someone other than the binding's definer, a database owner or an admin |
| `403-5` | `FORBIDDEN` |  | The trigger or schedule belongs to another user — a `SAVE_TRIGGER`, `DELETE_TRIGGER`, `SAVE_SCHEDULE` or `DELETE_SCHEDULE` replacing or removing a definition by someone other than its definer, a database owner or an admin |
| `403-6` | `FORBIDDEN` |  | Procedure source requires script MANAGE — a [`LIST_PROCEDURES`](#list_procedures) with `includeSource: true`, or a [`LIST_SCHEDULES`](#list_schedules) with `includeArgs: true`, by a caller with `READ` but not script `MANAGE`, ownership or admin |
| `404-1` | `NOT_FOUND` |  | User not found |
| `404-2` | `NOT_FOUND` |  | Entry not found |
| `404-3` | `NOT_FOUND` |  | No results |
| `404-4` | `NOT_FOUND` |  | Database not found |
| `404-5` | `NOT_FOUND` |  | No users found |
| `404-6` | `NOT_FOUND` |  | No index registered for the specified field |
| `404-7` | `NOT_FOUND` |  | Listen registration not found |
| `404-8` | `NOT_FOUND` |  | Procedure not found |
| `404-9` | `NOT_FOUND` |  | Trigger not found |
| `404-11` | `NOT_FOUND` |  | Collection not found — a write (`SAVE`, `BULK_SAVE`, `DELETE`) named a collection this node has no metadata for. Definitive only when clustering is off; a clustered node answers `503-10` instead, since it cannot tell "never existed" from "not replicated here yet" |
| `404-12` | `NOT_FOUND` |  | The collection has no schema — a [`GET_SCHEMA`](#get_schema) on a collection without one |
| `404-13` | `NOT_FOUND` |  | The snapshot does not exist: it was released or expired, or it belongs to another connection |
| `408-1` | `ERROR` |  | Script exceeded its time budget |
| `408-2` | `ERROR` |  | Script was cancelled |
| `408-3` | `ERROR` |  | The armed fault point was not reached in time (`AWAIT_FAULT`, testing only) |
| `408-4` | `ERROR` |  | The connection did not authenticate within `authTimeoutMs`; sent as a `CLOSE_CONNECTION` frame and the connection is closed |
| `408-5` | `ERROR` |  | The transaction was rolled back after reaching `transactionMaxAgeMs`, however active it was; answered to the next request |
| `408-6` | `ERROR` |  | The aggregation exceeded its time budget (`aggregateTimeoutMs`) |
| `409-1` | `ERROR` |  | User already exists |
| `409-2` | `ERROR` |  | Database already exists |
| `409-3` | `ERROR` |  | A transaction is already in progress for this connection |
| `409-4` | `ERROR` |  | No active transaction for this connection |
| `409-5` | `ERROR` |  | Could not acquire the collection lock in time; transaction aborted |
| `409-6` | `ERROR` |  | Operation not allowed while a transaction is open |
| `409-7` | `ERROR` |  | Transaction aborted: a participant could not prepare |
| `409-8` | `ERROR` |  | The procedure, trigger or schedule was modified by someone else |
| `409-9` | `ERROR` |  | The transaction was aborted and must be rolled back before continuing |
| `409-10` | `ERROR` |  | The transaction's outcome is indeterminate: part of it did not apply and recovery will re-drive it *(a `COMMIT_TRANSACTION` answer: the commit was decided, so never roll it back or send its writes again; in a cluster a re-sent `COMMIT_TRANSACTION` on the same connection finishes it, see [Transactions on the wire](#transactions-on-the-wire))* |
| `409-11` | `ERROR` |  | A name that differs only by case already exists and would share storage with it |
| `409-12` | `ERROR` |  | A previous transaction on this connection is still being resolved on a participant; retry once it finishes |
| `409-13` | `ERROR` |  | A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry |
| `409-14` | `ERROR` |  | The transaction already committed on its owner; it can no longer be rolled back |
| `409-15` | `ERROR` |  | The primary-key index cannot be rebuilt from the page files |
| `409-16` | `ERROR` |  | The connection's transaction was rolled back after `transactionIdleTimeoutMs` without a request; answered once, to the next request, which is not executed |
| `409-17` | `ERROR` |  | The snapshot was aborted and holds nothing any more; open a new one *(the message gives the reason: `snapshotMaxBytes`, a dropped collection or database, or a primary-key rebuild)* |
| `410-1` | `NOT_FOUND` |  | The listen ended: a collection it reads was dropped, its database was dropped, or the listening user can no longer read it. Pushed once on the listen's connection; the `listenId` is unregistered afterwards |
| `413-1` | `ERROR` |  | The connection left more than `clientOutboundQueueMaxBytes` of responses unread; its backlog is dropped, its listens end, and this `CLOSE_CONNECTION` frame is the last thing written before the connection is closed (a reader that never catches up does not see it) |
| `421-1` | `ERROR` |  | This node is not the owner of the target collection |
| `421-3` | `ERROR` |  | This read joins collections the transaction wrote on different nodes |
| `500-1` | `ERROR` |  | Error during authentication |
| `500-2` | `ERROR` |  | Error creating user |
| `500-3` | `ERROR` |  | Error deleting user |
| `500-4` | `ERROR` |  | Error changing password |
| `500-5` | `ERROR` |  | Error changing permissions |
| `500-6` | `ERROR` |  | Error while saving entries |
| `500-7` | `ERROR` |  | Error while saving entry |
| `500-8` | `ERROR` |  | Error while retrieving entry |
| `500-9` | `ERROR` |  | Error while processing aggregation |
| `500-10` | `ERROR` |  | Error while deleting entry |
| `500-11` | `ERROR` |  | Error while creating database |
| `500-12` | `ERROR` |  | Error updating database owners |
| `500-13` | `ERROR` |  | Error while dropping database |
| `500-14` | `ERROR` |  | Error while listing databases |
| `500-15` | `ERROR` |  | Error while creating collection |
| `500-16` | `ERROR` |  | Error while dropping collection |
| `500-17` | `ERROR` |  | Error while listing collections |
| `500-18` | `ERROR` |  | Error while creating index |
| `500-19` | `ERROR` |  | Error listing users |
| `500-20` | `ERROR` |  | Error while dropping index |
| `500-21` | `ERROR` |  | Error while reindexing |
| `500-22` | `ERROR` |  | Error while gathering database stats |
| `500-23` | `ERROR` |  | Error while processing listen operation |
| `500-24` | `ERROR` |  | Error while processing transaction operation |
| `500-25` | `ERROR` |  | Error while saving collection schema |
| `500-26` | `ERROR` |  | Error while deleting collection schema |
| `500-27` | `ERROR` |  | Error while saving the procedure |
| `500-28` | `ERROR` |  | Error while deleting the procedure |
| `500-29` | `ERROR` |  | Error while saving the trigger |
| `500-30` | `ERROR` |  | Error while deleting the trigger |
| `500-31` | `ERROR` |  | Error while saving the schedule |
| `500-32` | `ERROR` |  | Error while deleting the schedule |
| `500-33` | `ERROR` |  | A committed transaction could not be fully applied; its collections are fenced until it is finished *(the transaction keeps its locks and stays registered on the connection, which re-drives it with backoff, so a transient failure such as a full disk clears without a restart; a `ROLLBACK_TRANSACTION` answers `500-33` too, and other readers of its collections get `503-18`)* |
| `500-34` | `ERROR` |  | The write was logged durably but could not be applied; it is applied before the collection is next used, or at the next startup, so it may already have taken effect *(an unknown outcome: never resend it blindly; a read of the collection that still cannot apply the log answers `503-19`)* |
| `500-35` | `ERROR` |  | Error while gathering server info |
| `500-36` | `ERROR` |  | Error while opening the snapshot |
| `500-37` | `ERROR` |  | Error while reading the snapshot |
| `503-1` | `ERROR` |  | Max number of connections reached; sent as a `CLOSE_CONNECTION` frame and the connection is closed (with `tlsEnabled=true` it is closed before its handshake, with no frame) |
| `503-2` | `ERROR` | yes | Cluster does not have a write quorum |
| `503-3` | `ERROR` |  | Timed out waiting for the replication quorum *(the local commit **stands** and anti-entropy reconciles the lagging replicas — the write happened, so this is not a "it did not apply" error; blind retries are safe for `SAVE`/`DELETE`, which are idempotent, but not for a read-modify-write script)* |
| `503-4` | `ERROR` | yes | The collection's owner node is unreachable |
| `503-5` | `ERROR` | yes | Admin coordinator is synchronizing, retry shortly |
| `503-6` | `ERROR` |  | Too many scripts running, retry shortly *(the message names the scope that refused it: `node`, `user` or `database`)* |
| `503-7` | `ERROR` |  | The node the script was placed on did not report an outcome; it was not run again in case it already had *(only raised when a placed script's outcome could not be established; it is never re-run locally, so whether to retry is the caller's decision)* |
| `503-8` | `ERROR` |  | The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write *(distinct from `503-4`, which means the owner was provably never reached)*. Also the answer when the collection's ownership moved after the write was applied on this node, since `421-1` would claim it was not |
| `503-9` | `ERROR` | yes | The admin coordinator could not be resolved, retry shortly *(a coordinated admin op is never applied locally when no coordinator can be reached: an unreplicated DDL answered with `OK` would diverge the cluster silently)* |
| `503-10` | `ERROR` | yes | The collection has not reached this node yet, retry shortly *(a write routed to the collection's owner can arrive before the `CREATE_COLLECTION` that created it, since admin replication only waits for a quorum and the owner need not be in it — retryable, not a server fault)* |
| `503-11` | `ERROR` |  | The collection's schema could not be read, so the write cannot be validated; retry shortly *(also answered by `GET_SCHEMA`, and by writes to a collection whose stored schema holds a pattern this release refuses, until the schema is saved again)* |
| `503-13` | `ERROR` |  | The server is shutting down, retry against another node or later *(answered to every request on an already-open connection once shutdown has begun, except `ROLLBACK_TRANSACTION` and `CLOSE_CONNECTION`; a write acknowledged before this point is indexed and durable, and one refused with it was not applied)* |
| `503-14` | `ERROR` |  | Too many unauthenticated connections from this address (`maxUnauthenticatedConnectionsPerAddress`); sent as a `CLOSE_CONNECTION` frame and the connection is closed (with `tlsEnabled=true` it is closed before its handshake, with no frame) |
| `503-15` | `ERROR` |  | The server's listen registrations already keep `listenMaxRetainedBytes` of requests; retry once some end |
| `503-16` | `ERROR` | yes | The background index queue is full and nothing was written; retry shortly *(a `SAVE`, `BULK_SAVE`, `DELETE` or `COMMIT_TRANSACTION` that found `backgroundQueueMaxEvents` events queued and no room within `backgroundBacklogWaitMs`; a write forwarded to a full owner gets it at once. Writes inside a transaction are buffered and enqueue nothing, so only their commit is held back, and the transaction stays open)* |
| `503-17` | `ERROR` | yes | The server holds the maximum bytes of unsent responses; retry shortly *(the response would have pushed the encoded responses waiting to be written, across every connection, past `maxUnsentResponseBytes`; a response under 64Kb and the response to a write are never refused, so only a read gets it, and repeating the read is safe)* |
| `503-18` | `ERROR` | yes | The collection is held by a committed transaction that has not finished applying; retry shortly *(a non-transactional read waited `transactionLockTimeoutMs` for the commit to finish; nothing was read)* |
| `503-19` | `ERROR` | yes | The collection holds logged writes that could not be applied yet, so it cannot be read consistently; retry shortly *(a write to it answered `500-34` and applying its log before the read still failed, typically because the disk is still full; nothing was read, and repeating the read is safe)* |
| `503-20` | `ERROR` | yes | The server holds the maximum bytes of running aggregations; retry shortly *(every running pipeline together reached `aggregateTotalHeldBytes`; this one stopped and gave back what it held, so repeating it is safe)* |
| `503-21` | `ERROR` | yes | The server holds the maximum bytes of buffered transactions; the write was not buffered, retry shortly *(every open transaction together reached `transactionTotalMaxBytes`; nothing was buffered and the transaction stays usable)* |
| `503-22` | `ERROR` | yes | The server holds the maximum number of open snapshots; retry shortly *(`snapshotMaxOpen`)* |
