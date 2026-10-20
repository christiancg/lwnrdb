# LightWeigh Non-Relational DataBase

LWNRDB is a non-relational database written completely in Java. It's main focus is to be lightweight, small, fast and easy to start.
Database speed (measured in IOPS) is not paramount.

## Motivation

I like learning new stuff and always had some complaints about things I would like to do in the most common database systems. At the same time, I wondered how do databases work internally. What's a better way to learn that building a new database engine?

As such, this DB is not intended to be the fastest one out there, the most reliable or even the simplest: it's a learning exercise that could be helpful for someone in some cases. 

## Philosophy

- Simplicity is paramount: plain Java-next (always targeting the latest version and using new features) without any added libraries.
- Fast start-up times: currently less than a second
- Small size: less than a megabyte
- If there's some feature that you might need some time in the distant future, then you actually don't need it
- There's no need to support everything already supported by other DBs

## Design choices

- Saving and updating a record is the same thing if you provide the primary key. In fact there's no specific command to insert or update, just save.
- Querying is always done in an aggregation pipeline. The only exemption is while getting a record by id
- IDs are always strings and must follow the next rules: 
  - Between 1 and 64 characters
  - Only alphanumeric characters allowed and the following symbols are allowed: "_" and "-"
- All numbers are treated as a double (just for simplicity)
- Disk space is cheap: there's no compressing of special codification of files to save space
- Database and collection names must follow the next rules:
  - Between 3 and 64 characters
  - Only alphanumeric characters allowed and the following symbols are allowed: "_" and "-"
- Indexes are updated in the background and admin collections also. This is to make the DB a little bit more agile.
- No composed indexes (at least for now), but an aggregation pipeline can use many indexes (in fact will use all of them if possible)
- Each collection is split across pages (one file per page) sized up to `maxPageSize`; admin metadata about a collection's pages lives in a parallel paged collection under `admin/pages/<db>_<collection>`, and the pagination of that admin collection is itself tracked in memory only and rebuilt at startup (no `pages_pages_*` files on disk). New inserts use a first-fit search across existing pages, so space freed by deletions is reused before a new page is allocated.

## Status

LWNRDB is feature-complete for what it set out to be, and experimental in the sense the
Q&A below means: a learning exercise, not something to put production data in.

**What it does.** Documents are stored as newline-delimited JSON across paged files, queried
through an [aggregation pipeline](#aggregate) (the one exception is [`FIND_BY_ID`](#find_by_id))
with single-field indexes the planner combines freely, and reachable over a line-delimited
JSON socket protocol — optionally [TLS](#tls--secure-connections). On top of that:

| Area | |
|---|---|
| Types | documents, arrays, dates/times, [geo](#custom-operators-type-specific) (distance, within) and [vector](#custom-operators-type-specific) (semantic search) |
| Querying | [explain/analyze](#explain--analyze) with index suggestions, [listenable queries](#listen), [dirty reads](#concurrency--locking), index-only `COUNT` |
| Integrity | [per-collection JSON Schema](#schema-validation), [transactions](#transactions), [users and permissions](#users--permissions) |
| Scripting | a built-in JS engine — [ad-hoc scripts](#run_script), [stored procedures](#save_procedure), [triggers](#save_trigger) (after and before the write), [scheduled procedures](#save_schedule), [pipeline operators](#script-operators-simplejs-in-the-pipeline), and shared code via `import … from "procedures/<name>"`. See [docs/simplejs.md](docs/simplejs.md) |
| Clustering | fully-replicated nodes, no master, per-collection ownership, quorum writes, anti-entropy, cross-node transactions and script placement. See [docs/clustering.md](docs/clustering.md) |
| Operations | [`GET_DATABASE_STATS`](#get_database_stats), [`REINDEX`](#reindex), [standardized error codes](#error-codes), memory-bounded caching, graceful shutdown |

**Known non-goals.** No compound indexes (a pipeline uses as many single-field indexes as
apply), no sharding (every node holds a complete copy), no compression, and no attempt to
compete on raw IOPS.

**Still open.** Widening the JS engine's host surface as new database features need it — the
engine itself is closed (see [docs/simplejs.md](docs/simplejs.md) → *Engine status*).

## Wire Protocol / Message Reference

All messages are line-delimited JSON sent over a TCP connection. Every request must include a `type` field. Responses always contain `type`, `status` (`OK`, `ERROR`, `NOT_FOUND`, `UNAUTHENTICATED`, `FORBIDDEN`), and `message`. Error responses also include an `errorCode` field (absent on success) with the format `NNN-N` — a three-digit HTTP-style range prefix and a sequential number (e.g. `401-1`). The 4xx range covers client errors; 5xx covers server errors; 503 means the server is temporarily unavailable.

A request may nest objects and arrays at most 128 levels deep, counted from the request's own root object. A deeper request is refused as an invalid command, and a script's `db.save`/`db.bulkSave` refuses a document nested more than 128 levels. Documents stored before this limit existed stay readable.

#### Connection limits

Each request is one line terminated by `\n`; a single `\r` right before it is stripped. A lone `\r` does **not** end a line (it used to): it is kept as part of the request.

- **Line size.** A line may be at most `maxRequestSize` bytes (default `64Mb`) once the connection has authenticated, and at most **64Kb** before that (only `AUTHENTICATE` and `CLOSE_CONNECTION` are served unauthenticated, and each is a few hundred bytes; any other request is answered `401-1` without being validated). The server refuses as soon as the unterminated bytes pass the limit, without waiting for the newline: it answers one `CLOSE_CONNECTION` frame with `400-22` and closes the connection, because the rest of an oversized line cannot be skipped safely.
- **Token budget.** A line is also refused if it is denser than one JSON token per 4 bytes of the line limit (a 64Kb unauthenticated line may hold 16 384 tokens, a 64Mb one 16 777 216). Real documents, vectors and pipelines average well above 4 bytes per token; a huge flat array such as `[1,1,1,...]` does not. Such a line is answered `The command is not valid` and the connection stays open.
- **Authentication deadline.** A connection must authenticate within `authTimeoutMs` (default `10000`) of being accepted. The deadline is absolute: traffic, blank lines and failed `AUTHENTICATE` attempts do not extend it. When it expires the server answers one `CLOSE_CONNECTION` frame with `408-4` and closes the connection. With `tlsEnabled=true` the TLS handshake runs under the same deadline: a client that stalls or drips its handshake is closed at `authTimeoutMs` plus one second. A TLS connection over `maxConnections` or `maxUnauthenticatedConnectionsPerAddress` is closed before its handshake, with no frame (the refusal could only be sent after the handshake it would cost). Once authenticated, a connection is never closed for being idle.
- **Unauthenticated connections per address.** One remote address may hold at most `maxUnauthenticatedConnectionsPerAddress` (default `16`) connections that have not authenticated yet; the next one is answered `503-14` and closed. Authenticating frees the slot. Clients behind one NAT address share the allowance.
- **Invalid requests before authentication.** A connection that sends `maxUnauthenticatedInvalidRequests` (default `5`) requests before it authenticates that are not a successful `AUTHENTICATE` (unparseable lines, requests answered `401-1`, failed attempts) is closed after the last answer.
- **Slow readers.** Every response and LISTEN update goes through a per-connection outbound queue written by its own thread, so a client that stops reading never blocks the server: its open transaction is still rolled back after `transactionIdleTimeoutMs`, and the LISTEN worker keeps notifying everyone else. Once the unread backlog passes `clientOutboundQueueMaxBytes` (default `16Mb`, counted in UTF-8 bytes as written to the socket) the backlog is dropped, the connection's listens end, one `CLOSE_CONNECTION` frame with `413-1` is queued and the connection is closed. A single response larger than the cap is still delivered when nothing is waiting behind it.
- **Idle transactions.** A connection holding an open transaction must send its next request within `transactionIdleTimeoutMs` (default `30000`), and a transaction is rolled back once it is older than `transactionMaxAgeMs` (default `600000`) however busy it is; see [Transactions](#transactions).
- `maxConnections` counts client sockets only; forwarded cluster requests and transaction sessions no longer take a slot.

```jsonl
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The request exceeds the maximum allowed size","errorCode":"400-22"}
{"type":"CLOSE_CONNECTION","status":"ERROR","message":"The connection did not authenticate in time","errorCode":"408-4"}
```

### Naming rules

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

### Operations

#### `LIST_DATABASES`
Requires authentication (an unauthenticated connection gets `401-1`). An admin sees every database; any other
user sees only the databases they own or hold a grant on: a `databasePermissions` entry, a `collectionPermissions`
entry for one of its collections (`database|collection`), or a `scriptPermissions` level other than `NONE`. A
database the caller cannot reach is simply left out of the list.
```json
{"type":"LIST_DATABASES"}
```

#### `CREATE_DATABASE`
```json
{"type":"CREATE_DATABASE","databaseName":"my_db"}
```
Refused with `400-41` once the server already holds `maxDatabases` user databases.

#### `DROP_DATABASE`
```json
{"type":"DROP_DATABASE","databaseName":"my_db"}
```

#### `LIST_COLLECTIONS`
```json
{"type":"LIST_COLLECTIONS","databaseName":"my_db"}
```

#### `CREATE_COLLECTION`
```json
{"type":"CREATE_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```
Refused with `400-40` once the database already holds `maxCollectionsPerDatabase` collections; re-creating an existing collection is always allowed.

#### `DROP_COLLECTION`
```json
{"type":"DROP_COLLECTION","databaseName":"my_db","collectionName":"my_coll"}
```

#### `SAVE` (insert or update)
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

#### `BULK_SAVE`
At least one object required.
```json
{"type":"BULK_SAVE","databaseName":"my_db","collectionName":"my_coll","objects":[{"name":"Alice"},{"_id":"user-2","name":"Bob"}]}
```

#### `FIND_BY_ID`
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```
Read operations accept an optional `"dirtyRead": true` (default `false`); see [Concurrency & locking](#concurrency--locking).
```json
{"type":"FIND_BY_ID","databaseName":"my_db","collectionName":"my_coll","_id":"user-1","dirtyRead":true}
```

#### `DELETE`
```json
{"type":"DELETE","databaseName":"my_db","collectionName":"my_coll","_id":"user-1"}
```

#### `AGGREGATE`
Queries run through a pipeline of steps. `aggregationSteps` may be empty (returns all documents). Accepts an optional top-level `"dirtyRead": true` (default `false`); see [Concurrency & locking](#concurrency--locking).
Also accepts an optional top-level `"analyze": true` (default `false`); see [Explain / Analyze](#explain--analyze).
A pipeline is stopped with `408-6` once it has run for `aggregateTimeoutMs` (default `300000`, `0` = no limit). An `IN`/`NOT_IN` list or a `within` polygon may hold at most `maxFilterOperands` (default `10000`) operands, refused with `400-27` past it; the same limit caps the operands of one conjunction (`AND`/`OR`/…), the total operator nodes of one `FILTER` step, and the operators plus mid-operator operands of one `MAP` step. A `MAP` `fieldName` may nest at most 128 levels. One output row of a `JOIN` may carry a joined array of at most `maxEntrySize` bytes, refused with `400-28` past it (the same refusal whether the remote field is indexed or scanned). The whole pipeline may build at most `aggregateMaxResultBytes` (default `256Mb`): the strings every `MAP` adds through `CONCAT` and string `CAST`s, the values its `SCRIPT` mid-operators add (measured as their JSON text), each `JOIN` row's joined array (counted once per row, even when rows share it) and one byte per result row are totalled per request, and past it the pipeline stops with `400-36`. A `LISTEN` re-run is held to the same budget. `GROUP_BY`, `DISTINCT` and `JOIN` key their lookups on a hash seeded per process, and so do the index reads and the `CREATE_INDEX` build behind them, so values that collide under Java's string hash cannot pile into one bucket.

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

`GROUP_BY`, `JOIN`, `SORT`, and `DISTINCT` use a single-field index when one exists on the step's field and the step is the first step in the pipeline; otherwise they fall back to a full scan. These steps use only the scalar/custom/null indexes, so documents whose indexed field holds a JSON object or array are not represented in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results (see [Memory management → Streaming reads](#memory-management)). 
Object- and array-valued fields are instead indexed for **element-match** (whole-value equality): a `FILTER` with `EQUALS`, `NOT_EQUALS`, `IN`, or `NOT_IN` hashes the object/array and resolves it through a dedicated per-kind hash index (`…-Object.idx` / `…-Array.idx`).

A collection may also carry a single JSON Schema stored alongside its data files as `{collection}-schema.json` (see [Schema validation](#schema-validation)). Ordering/containment operators (`GREATER_THAN*`, `SMALLER_THAN*`, `CONTAINS`) and the reconstructing steps above cannot use these hash indexes because a hash cannot be ordered or turned back into a value.

**Field operator types:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`

`EQUALS`, `NOT_EQUALS`, `IN` and `NOT_IN` all share one notion of equality: strings compare case-insensitively, custom types compare semantically (so `#datetime(2024-01-01T10:00)` equals `#datetime(2024-01-01T10:00:00)`), numbers compare by value, and objects and arrays compare as whole values — element by element, where a nested custom value still compares semantically and a nested string compares exactly. The same holds for `CONTAINS` of a scalar in an array-valued field, except that a string element there compares exactly. An object/array hash index written before semantic hashing of nested custom values needs one `REINDEX` of that field. The primary key is the one exception — a `FILTER` on `_id` compares exactly, matching `FIND_BY_ID`, `SAVE` and `DELETE`. `CONTAINS` on a string is a case-sensitive substring test. A `null` operand is only ever an equality test: `EQUALS null` returns exactly the documents whose field is `null` and `NOT_EQUALS null` exactly those whose field is not, while the ordering operators and `CONTAINS` return nothing. Against a non-null operand, a document whose field is `null` is matched by `NOT_EQUALS` — `null` equals no other value — and by no other operator. In an `IN`/`NOT_IN` list, `null` is a value like any other: a `null` field is a member of a list holding `null` (`IN [1, null]` matches it, `NOT_IN [1, null]` does not) and of no other list. A document that lacks the field is excluded by every operator.

`SORT` orders by that same comparison, so the ordering operators and the sort agree: a custom type is ordered by its own comparator (chronologically for `#datetime`/`#time`, by geohash for `#geo`, by SimHash signature for `#vector`), never by the text of its wire form. Two values the comparison calls equal — `#datetime(2024-01-01T10:00)` and `#datetime(2024-01-01T10:00:00)` — therefore tie in a `SORT` and break on `_id`, exactly as two equal numbers or strings do. On a field that holds both plain strings and custom values, every custom value sorts after every plain string; values of different types are ranked boolean, number, string, custom, and that ranking is identical on every node.

Every field operator requires a `value`, including the ones whose operand is conceptually empty — omitting the member is refused with `400-1` rather than being read as a null operand. Spell a null operand as an explicit JSON `null` (`"value": null`).

**Conjunction operator types:** `AND`, `OR`, `NOR`, `XOR`, `NAND`

#### The `CAST` mid-operator

A `MAP` `ADD_FIELD` may carry a `CAST` mid-operator (`fieldName`, `toType`, plus `customTypeName` for `JSON_CUSTOM`). A cast that cannot produce a value of the target type sets the field to `null` rather than to a manufactured one, on every target type:

- `NUMBER` accepts only the number syntax the wire protocol itself accepts — digits, a leading `-`, a decimal point, and an `e`/`E` exponent. Java-only spellings (`0x1p3`, `1d`, `+1`), surrounding whitespace, `Infinity` and `NaN` all answer `null`, as does an exponent that overflows a double. An integral result narrows exactly as a wire-parsed number does, so a cast `5` and a stored `5` are indistinguishable.
- `BOOLEAN` accepts `true` and `false` case-insensitively; every other string answers `null`. A number casts to `value != 0`.
- `STRING` renders a number with the same formatter that writes documents, and a custom type as its data value.
- `JSON_CUSTOM` answers `null` when the source string is not a valid value of the named type.

#### The `CONCAT` mid-operator

`CONCAT` joins its operands into one string, which may be at most `maxEntrySize` characters long; a longer result stops the pipeline with `400-28`. The strings one `MAP` step adds to a document through `CONCAT` and string `CAST`s are also totalled per document, and a document whose additions pass `maxEntrySize` stops the pipeline with `400-28` too, however small each one is. A `SCRIPT` mid-operator's value counts the same way, measured as its JSON text. A top-level string operand is a **field path** unless it carries the string-literal prefix `-`; a string inside an array operand is always a literal. A `null` operand contributes the text `null`, and so does a top-level field path the document does not hold — in both operand positions, and whether the null was written literally or read from a null-valued field.

#### Script operators (SimpleJS in the pipeline)

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

#### Custom operators (type-specific)

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

Vector values are stored and read through ordinary `SAVE`/`FIND_BY_ID`/`AGGREGATE` (e.g. `"embedding":"#vector(0.12,0.44,0.91)"`), and a vector field is indexable like any other. The vector field index is sorted by a **SimHash (locality-sensitive) signature**, so `nearest` pre-filters candidates from the neighbourhood of the query's signature in the sorted index and then re-scores each fetched document exactly. Because SimHash is locality-sensitive but not exact, this index path is **approximate (ANN)**: a query may miss matches that fall outside the scanned neighbourhood. Pass `"exact":true` to force an exact full scan instead (guaranteed top-`k`, no index). A `COUNT` after a `nearest` filter always reads the matched documents (the ranked candidates cannot be counted from ids alone), and returns `min(k, matches)`.

#### Explain / Analyze

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

#### `CREATE_INDEX`
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```
Answers `404-11` when the collection does not exist (`503-10` on a cluster node it has not reached yet), like the write operations; `script_runs` can be indexed once the first recorded run has created it. Creating an index that already exists answers `OK` without rebuilding it. Creating one past `maxIndexesPerCollection` is refused with `400-42`.

#### `DROP_INDEX`
```json
{"type":"DROP_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

#### `REINDEX`
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

#### `SAVE_SCHEMA`
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

#### `DELETE_SCHEMA`
Removes the collection's schema, so writes are no longer validated. Idempotent — returns `OK` whether or not a schema existed. Requires admin privileges or database ownership.
```json
{"type":"DELETE_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```

#### `GET_SCHEMA`
Returns the collection's schema exactly as it was saved. Requires `READ` on the collection.
```json
{"type":"GET_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```
```json
{"type":"GET_SCHEMA","status":"OK","message":"Ok","schema":{"type":"object","required":["_id"]}}
```
Answers `404-12` when the collection has no schema, `404-11` when the collection does not exist, and `503-11` when
the stored schema cannot be read (an unreadable schema is reported, never mistaken for no schema).

#### `LISTEN`

Subscribe to live query results. The server runs the aggregation once and returns the initial results with a UUID listen ID and a SHA-256 hash of the result set. Whenever documents in the collection change, the server re-runs the query in the background; if the new hash differs from the last sent hash, a push message is sent to the same connection with the updated results.

Accepts the same `aggregationSteps` as `AGGREGATE` (all step types, same validation rules). Requires `READ` on the base collection; `JOIN` steps additionally require `READ` on each joined collection. A connection may hold at most `maxListensPerConnection` (default `64`) registrations and the server at most `maxListens` (default `10000`); past either, `LISTEN` answers `400-25` without running the query. One user may hold at most `maxListensPerUser` (default `256`, `0` = unlimited) across all of their connections; past it, `400-38`. A registration keeps its request for as long as it lives, so a `LISTEN` line longer than `listenMaxRequestBytes` (default `1Mb`) is refused with `400-37` before it runs, and the requests every registration keeps (counted as each serialized request's length) may total at most `listenMaxRetainedBytes` (default `64Mb`) on the server; past it, `LISTEN` answers `503-15` until registrations end. The bytes and the per-user count are given back when a registration stops, ends, or its connection closes. Each background re-run is bounded by `listenRerunTimeoutMs` (default `10000`) rather than `aggregateTimeoutMs`; a re-run past it is logged and pushes nothing, and the next change to a collection it reads re-runs it again.

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
  "listenId": "550e8400-e29b-41d4-a716-446655440000",
  "results": [ "..." ],
  "resultHash": "a3f5...64-hex-chars...d91e"
}
```

Push message (sent asynchronously when results change):
```json
{
  "type": "LISTEN",
  "status": "OK",
  "message": "Query results updated",
  "listenId": "550e8400-e29b-41d4-a716-446655440000",
  "results": [ "..." ],
  "resultHash": "b7c2...64-hex-chars...f308"
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

#### `STOP_LISTEN`

Cancel a specific listen subscription by its ID.

```json
{"type": "STOP_LISTEN", "listenId": "550e8400-e29b-41d4-a716-446655440000"}
```

Returns `NOT_FOUND` (`404-7`) if the listen ID is not registered.

#### Transactions

A connection may open **one transaction at a time**. While a transaction is open the connection's data mutations (`SAVE`, `BULK_SAVE`, `DELETE`) are **buffered** instead of applied: they are recorded in an internal `admin/transactions` collection and are not written to the real collections until commit.

The connection's own reads (`FIND_BY_ID`, `AGGREGATE`) see its buffered writes (**read-your-writes**); other connections do not. The first buffered write to a collection takes that collection's exclusive write lock and holds it until commit/rollback, so no other connection can read or write those collections meanwhile (this is what preserves atomicity). 

See [Concurrency & locking](#concurrency--locking).

While a transaction is open only `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID`, `AGGREGATE`, `COMMIT_TRANSACTION`, `ROLLBACK_TRANSACTION` and `CLOSE_CONNECTION` are accepted; any other operation is rejected with `409-6`. 

If a buffered write cannot take a collection's write lock within `transactionLockTimeoutMs` the transaction is aborted: that statement answers `409-5`, its buffered writes are discarded and its locks released. The transaction stays open but unusable — every further `SAVE`, `BULK_SAVE`, `DELETE`, `FIND_BY_ID` and `AGGREGATE` answers `409-9`, and so does `COMMIT_TRANSACTION`. Send `ROLLBACK_TRANSACTION` to end it and start again. This is deliberate: a retried statement must never silently commit on its own outside the transaction the client still believes it is in.

If the connection closes with a transaction still open, it is automatically rolled back.

**Idle transactions.** A transaction's write locks block every other connection on its collections, so a connection may not sit silent while it holds one. If no complete request arrives within `transactionIdleTimeoutMs` (default `30000`; a line dribbled in byte by byte does not count until it ends), the server rolls the transaction back on the connection's own thread: its buffered writes are discarded and its locks released, so blocked writers proceed. The connection stays open. Its **next request, whatever it is, is answered `409-16` once and not executed** — including `COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` — so a client that still believes it is inside the transaction cannot have a write run on its own by mistake. Only `CLOSE_CONNECTION` is exempt. After that one answer the connection behaves normally and a new `START_TRANSACTION` works. A transaction whose commit is half-applied (`500-33`) or that was already aborted (`409-5`) is never rolled back this way. The timeout only runs while the server waits for the next request: a long-running request is never cut short.

```json
{"type":"SAVE","status":"ERROR","message":"The transaction was rolled back after being idle too long","errorCode":"409-16"}
```

**Maximum age.** A transaction older than `transactionMaxAgeMs` (default `600000`, `0` = no limit) is rolled back the same way however busy the connection is, so a client cannot hold its write locks forever by sending a request every few seconds. Its next request is answered `408-5` once and not executed, exactly like the idle case.

**Size.** One transaction may buffer at most `transactionMaxOps` operations (default `100000`; a `BULK_SAVE` counts one per document) and `transactionMaxBytes` of documents (default `256Mb`). A buffered `DELETE` counts the bytes of what it buffers: its id, plus the whole deleted document when a `DELETED` trigger will receive it. The write that would pass either is refused with `400-26`; nothing of it is buffered and the transaction stays usable, so the client can still commit what it buffered or roll back.

**Grants are checked again at commit.** Every operation is authorized when it is buffered, and `COMMIT_TRANSACTION` authorizes every collection the transaction writes once more against the user's current grants. If a grant was revoked in between, the commit is refused with `403-1`, the whole transaction is rolled back and nothing is applied.

##### `START_TRANSACTION`
```json
{"type":"START_TRANSACTION"}
```
Response carries the new transaction id:
```json
{"type":"START_TRANSACTION","status":"OK","message":"Transaction started","transactionId":"<uuid>"}
```
Returns `409-3` if a transaction is already open on the connection.

##### `COMMIT_TRANSACTION`
Applies the buffered operations to the real collections in order, then removes the buffered records and releases the held locks.
```json
{"type":"COMMIT_TRANSACTION"}
```

##### `ROLLBACK_TRANSACTION`
Discards the buffered operations (nothing is written to the real collections), removes the buffered records and releases the held locks.
```json
{"type":"ROLLBACK_TRANSACTION"}
```
`COMMIT_TRANSACTION` and `ROLLBACK_TRANSACTION` return `409-4` if no transaction is open.

##### `LIST_TRANSACTIONS` (admin only)
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

##### `RESOLVE_TRANSACTION` (admin only)
Manually forces the outcome of an in-doubt distributed transaction identified by `dtxId` (from `LIST_TRANSACTIONS`). `decision` must be `"commit"` or `"abort"`; the decision is broadcast to all members so every participant applies it. Use only after confirming the correct outcome — see [docs/clustering.md](docs/clustering.md).
```json
{"type":"RESOLVE_TRANSACTION","dtxId":"<uuid>","decision":"commit"}
```
```json
{"type":"RESOLVE_TRANSACTION","status":"OK","message":"Transaction committed"}
```
Returns `400-1` if `dtxId` is missing or `decision` is not `commit`/`abort`.

#### `RUN_SCRIPT`
Runs a JavaScript program inside the database (see [docs/simplejs.md](docs/simplejs.md) for the engine). The script is **scoped to one database** — the `databaseName` of the request — and may use any collection in it. Enabled by default; set `scriptsEnabled=false` to refuse the operation with `403-2`. Being allowed to run one still requires admin privileges, database ownership, or `scriptPermissions` on that database.

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
- **Run history**: unless `scriptRunHistoryEnabled=false`, a finished run is recorded in the reserved `script_runs` collection of its database, queryable with an ordinary `AGGREGATE`. Ad-hoc `RUN_SCRIPT` is opt-in via `scriptRunHistoryKinds`; the collection is created lazily and refused to client writes.
- **Errors**: `403-2` scripting disabled, `403-1` caller may not run scripts, `404-4` unknown database, `400-10` source over `scriptMaxSourceBytes`, `400-9` threw or would not parse (`message` is `"<ErrorName>: <message>"`, the name and message each clipped to `scriptMaxLogLineChars` like every stack frame; an engine failure with no script-level meaning reads `InternalError: Internal script error`, the detail going only to the server log), `400-11` instruction or depth budget, `400-12` over `scriptMaxMemoryBytes`, `400-15` result over `scriptMaxResultBytes`, `400-30` an array-like past `scriptMaxArrayLength`, `400-20` result promise never settled, `408-1` over `scriptTimeoutMs`, `408-2` cancelled, `503-6` a concurrency ceiling refused it (the `message` names which of `node`/`user`/`database`; nothing ran, so it is retryable), `503-7` the run was placed on another node that never reported an outcome — it is deliberately **not** re-run here, because it may already have run there, so retrying is only safe if the script is idempotent, `409-6` sent with a transaction open.
- Under clustering the run is **placed** on a live node chosen by script load and ownership share (`scriptRoutingEnabled`, on by default); each operation it issues is then routed to its collection's owner, and `db.transaction` spans owners through the same 2PC the wire protocol uses.

#### `SAVE_PROCEDURE`
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

#### `DELETE_PROCEDURE`
```json
{"type":"DELETE_PROCEDURE","databaseName":"mydb","name":"recalcTotals"}
```
Idempotent — succeeds whether or not the procedure existed. Refused with `400-14` while a trigger still references it (the message names the trigger). For a caller who is not the definer of a binding that reaches the procedure (see [`SAVE_PROCEDURE`](#save_procedure)), a database owner or an admin, `403-4` is answered first and names no trigger.

#### `LIST_PROCEDURES`
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

#### `CALL_PROCEDURE`
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

#### `LIST_SCRIPTS` (admin only)
Lists every script run executing **anywhere in the cluster** right now — ad-hoc `RUN_SCRIPT`s, `CALL_PROCEDURE`s, trigger runs and scheduled runs alike. It runs on the node that receives it and queries every live member, so an operator sees a run wherever [script placement](docs/clustering.md) put it, not only on the node they connected to. An unreachable peer costs its rows a warning in the log rather than failing the listing.
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

#### `CANCEL_SCRIPT` (admin only)
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
- **A cancelled trigger run is dropped, not retried.** Trigger execution is otherwise exactly-once and a failed run is retried up to `triggerMaxAttempts`, but a cancellation consumes the run's pending record — cancelling a runaway trigger stops it rather than queueing it up again. See [docs/simplejs.md](docs/simplejs.md).

#### `LIST_TRIGGER_RUNS` (admin only)
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

#### `RESOLVE_TRIGGER_RUN` (admin only)
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

#### `SAVE_TRIGGER`
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

##### `timing: "before"` — validating and mutating hooks

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

#### `TEST_TRIGGER`
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

#### `DELETE_TRIGGER`
```json
{"type":"DELETE_TRIGGER","databaseName":"mydb","collectionName":"orders","name":"auditWrites"}
```
Idempotent — succeeds whether or not the trigger existed. Requires admin privileges, ownership, or `MANAGE` with `READ_WRITE` on the collection. A trigger installed by another user can only be deleted by that user (its `definer`), a database owner or an admin; anyone else is refused with `403-5`. The same rule applies to [`SAVE_TRIGGER`](#save_trigger) replacing an existing trigger of the same name.

#### `LIST_TRIGGERS`
```json
{"type":"LIST_TRIGGERS","databaseName":"mydb","collectionName":"orders"}
```
Omit `collectionName` to list every trigger in the database. Each entry carries its `collectionName` and `definer`. Requires `READ` on the database.

#### `SAVE_SCHEDULE`
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
- **Delivery is at-most-once per due instant.** Firing is never finer-grained than `scheduleTickMs`, a run still executing when the next occurrence is due is skipped rather than queued twice, and **missed runs while a node was down are skipped, not caught up** — a job that must not miss an occurrence should be idempotent and driven off data, not off the clock. Under clustering each schedule is owned by exactly one node through the consistent-hash ring, so a schedule fires once per due instant across the cluster and fails over automatically; a membership change during a tick may drop that tick rather than run it twice (see [docs/clustering.md](docs/clustering.md) → *Scheduled procedures*).
- A schedule is stored **with its database**, in `{filePath}/{database}/.schedules/{name}.json`, so dropping the database removes it.
- **Errors**: `403-2` schedules disabled, `403-1` not permitted, `403-5` replacing another user's schedule, `404-4` unknown database, `404-8` unknown procedure, `400-1` an invalid name (3–64 alphanumerics plus `_` and `-`), `400-16` neither or both of `cron`/`intervalMs`, a malformed cron, or a negative `timeoutMs`, `400-17` past `scheduleMaxPerDatabase`, `400-29` a `timeoutMs` past `scheduleMaxTimeoutMs`, `400-33` past `scheduleMaxTotalPerUser`, `400-35` an `intervalMs` (or a `cron` that fires) more often than `scheduleMinIntervalMs` allows, `409-8` version conflict.
- **A full schedule queue never drops another database's run.** When `scheduleQueueSize` is reached, the oldest queued run of the same database is dropped to make room; if that database has nothing queued, the new run is the one dropped. Triggers follow the same rule on `triggerQueueSize` and `triggerQueueMaxBytes`.

#### `DELETE_SCHEDULE`
```json
{"type":"DELETE_SCHEDULE","databaseName":"mydb","name":"nightlyRollup"}
```
Idempotent — succeeds whether or not the schedule existed. A schedule installed by another user can only be deleted by its definer, a database owner or an admin (`403-5`). A [`DELETE_PROCEDURE`](#delete_procedure) is refused with `400-16` while a schedule still references it (the message names the schedule).

#### `LIST_SCHEDULES`
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

#### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

### Schema validation

A collection can carry a single **JSON Schema (draft 2020-12)** that every write must satisfy. Attach one with [`SAVE_SCHEMA`](#save_schema) and remove it with [`DELETE_SCHEMA`](#delete_schema) (both require admin privileges or database ownership). While a schema is in force, every `SAVE` and `BULK_SAVE` document is validated **before it is committed**, so a non-compliant document never reaches the collection:

- A non-compliant `SAVE` is rejected with `400-7` and the document is not written.
- A `BULK_SAVE` is **all-or-nothing**: if any document violates the schema the whole batch is rejected with `400-7` (the message names the offending `_id`), and nothing is written.
- Collections without a schema are unconstrained (existing behaviour is unchanged).
- Validation is **bounded**. Each write request gets an evaluation budget of `schemaValidationBudget` steps (default 5 000 000), shared by every document of a `BULK_SAVE`: one step per schema node evaluated against a value, plus one per step of the regex matcher running a `pattern`, `patternProperties` or `propertyNames` regex (a regex with many capture groups costs more per start position, one extra step per 32 groups, and comparing a literal or a backreference costs one extra step per 64 characters compared). A document that exhausts it is refused with `400-23` and nothing is written, so a catastrophic pattern such as `(x+x+)+y` or an exponential `$ref` diamond cannot hold the collection write lock indefinitely. In a `BULK_SAVE` the whole batch is refused, inside a transaction the write answers `400-23` and the transaction stays open, and a before trigger whose replacement document exhausts the budget is rejected with `400-21`. The budget is a step count, not a deadline, so nodes configured with the same `schemaValidationBudget` accept and refuse the same document (keep it equal on every node of a cluster; the owner's check under the collection lock is the one that decides); legitimate 1 Mb documents cost a few hundred thousand steps at most. Cheap documents against a schema with a dangerous pattern still pass: only the documents that actually blow the budget are refused.

The reserved `_id` field is **excluded** from validation (it is a system-assigned primary key, already format-checked), so a schema with `"additionalProperties": false` does not need to declare it.

**Supported keywords** (a pragmatic subset of 2020-12): `type` (incl. `integer` vs `number`), `enum`, `const`; object — `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `minProperties`/`maxProperties`, `dependentRequired`, `dependentSchemas`; array — `prefixItems`, `items`, `minItems`/`maxItems`, `uniqueItems`, `contains`/`minContains`/`maxContains`; string — `minLength`/`maxLength`/`pattern` (`pattern`, `patternProperties` keys and `propertyNames` patterns use ECMA-262 syntax in Unicode mode, as 2020-12 specifies — the same engine as SimpleJS's `RegExp` with the `u` flag, so `$` does not match before a trailing newline and `\s` includes Unicode whitespace; Java-only syntax such as `a++` or `(?i)` is refused with `400-8`, and a schema stored with such a pattern before this rule refuses writes with `503-11` until it is re-saved); number — `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`; applicators — `allOf`/`anyOf`/`oneOf`/`not`/`if`/`then`/`else`; and local `$ref` (JSON-Pointer references within the same schema, e.g. `#/$defs/foo`) with `$defs`; a `$ref` target is validated as a schema wherever it lives, so one pointing into an unrecognized keyword such as draft-07 `definitions` is checked rather than trusted. `format` is accepted but **non-asserting** (annotation only). Metadata keywords (`$schema`, `$id`, `title`, `description`, …) are accepted; any **unrecognized** keyword is ignored but surfaced as a non-fatal `warning` on the `SAVE_SCHEMA` response. Known-but-unimplemented keywords (`unevaluatedProperties`/`unevaluatedItems`, `$dynamicRef`, remote `$ref`, `$vocabulary`) are **rejected** by `SAVE_SCHEMA` (`400-8`) so a schema author is never misled into believing an unenforced constraint applies.

**Custom (EJson) types.** Beyond the standard `type`, a dedicated `customType` keyword asserts one of the extended types (`geo`, `vector`, `datetime`, `time`), e.g. `{"customType":"geo"}` requires a `#geo(lat,lng)` value. Custom-typed values also satisfy `"type":"string"` (they are stored as strings), but only `customType` distinguishes a geo from an arbitrary string.

Schemas are **user data**: each is stored as `{collection}-schema.json` in the collection's folder and cached in memory so validation adds negligible per-write cost. Their compiled regular expressions are cached node-wide, weighed by estimated heap against `schemaPatternCacheMaxBytes` (least recently used first out). Under clustering they are replicated to every node (the schema op is coordinator-serialized DDL, and the coordinator ships the resulting versioned schema record to its peers) and reconciled by admin anti-entropy, so a node that was down during a `SAVE_SCHEMA`/`DELETE_SCHEMA` catches up on rejoin; a deleted schema leaves a permanent tombstone, so it is never brought back.

### Users & Permissions

Every connection must authenticate before sending any protected operation. `AUTHENTICATE` and `CLOSE_CONNECTION` are the only operations that do not require authentication (`LIST_DATABASES` used to be public; it now answers `401-1` before authenticating).

#### `AUTHENTICATE`
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
  run at once (default: one per available processor); the rest queue.

#### `CREATE_USER` (admin only)
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

#### `DELETE_USER` (admin only)
```json
{"type":"DELETE_USER","username":"bob"}
```

#### `CHANGE_PERMISSIONS` (admin only)
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

#### `SET_PASSWORD`
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

#### `SET_DATABASE_OWNERS` (admin only)
Replaces the full owners list for a database. All usernames must already exist. The creator of a database is automatically set as its first owner.
```json
{"type":"SET_DATABASE_OWNERS","databaseName":"my_db","owners":["Alice","bob"]}
```

#### `GET_DATABASE_STATS`
Returns memory usage, totals, and per-database/per-collection breakdown. Useful for monitoring eviction and tuning `maxMemory`. `memory.userCacheBytes` is the modelled heap of the user cache (the total `maxMemory` is enforced against); the `heap*` fields are the JVM's measured heap.

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

#### `LIST_USERS` (admin only)
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

### Permission model

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

### Authentication errors

| Situation | `status` | `errorCode` | `message` |
|---|---|---|---|
| Request sent before authenticating | `UNAUTHENTICATED` | `401-1` | `Must authenticate first` |
| Authenticated user was deleted mid-session | `UNAUTHENTICATED` | `401-2` | `User no longer exists` |
| Wrong username or password | `ERROR` | `401-3` | `The user doesn't exist or the wrong credentials have been provided` |
| Too many failed `AUTHENTICATE` attempts on one connection (the server then closes it) | `UNAUTHENTICATED` | `401-4` | `Too many failed authentication attempts on this connection` |
| Insufficient permissions | `FORBIDDEN` | `403-1` | `Action is forbidden, no permissions` |

### Fault injection (testing and debugging only)

These three operations are **not for applications**. They exist so the cluster test suite can make one node fail at an exact statement inside the engine — freeze there, crash there, or drop one reply to a peer — which is how it reproduces failures that only happen *between* two adjacent steps. They are inert unless the server was started with `faultInjectionEnabled=true` (default `false`); otherwise each one answers `403-3`. All three are admin only, act on the node the client is connected to (they are never forwarded), and are refused inside an open transaction.

A fault is armed at a named **point**. It fires once, the first time a thread reaches the point with a matching subject, and then stays inert until it is armed again.

| Point | Where it fires | Subject (`match`) |
|---|---|---|
| `WRITE_OWNERSHIP_RECHECKED` | A clustered write re-checked, under its collection lock, that this node owns the collection, and has not applied yet | `<db>\|<collection>` |
| `DELETE_TOMBSTONE_RESERVED` | A clustered delete wrote its tombstone and has not deleted the document yet | `<db>\|<collection>` |
| `ANTI_ENTROPY_SNAPSHOTTED` | An anti-entropy round took its snapshot and released its lock, and has not sent its digest yet | `<db>\|<collection>` |
| `PK_INDEX_REPLACED` | A `REINDEX` with `"primaryKey": true` wrote the rebuilt `pk.idx` and has not rebuilt the page rows or field indexes yet | `<db>\|<collection>` |
| `INDEX_BATCH_FLUSHED` | A background field-index batch appended to the first of the type logs it changed and has not appended to the others yet | `<db>\|<collection>` |
| `PAGE_COPY_APPENDED` | An update, relocation or bulk update logged its record in the write-ahead log and has not written its copies, `pk.idx` rows or blanks yet | `<db>\|<collection>` |
| `PK_ROW_COMMITTED` | An update, relocation, bulk update or delete wrote everything its logged record describes: the new copies, the `pk.idx` rows and the blanks of the copies they supersede | `<db>\|<collection>` |
| `PAGE_VACUUM_RENAMED` | A page vacuum moved its rewritten page into place and has not appended the moved records' rows to `pk.idx` yet | `<db>\|<collection>` |
| `WAL_APPENDED` | Any write appended its record to the collection's write-ahead log (and forced it with `syncWrites`) and has not written the pages or `pk.idx` yet | `<db>\|<collection>` |
| `WAL_CHECKPOINT_FORCED` | A checkpoint forced the pages and `pk.idx` the log touched and has not rewritten the log as a single checkpoint yet | `<db>\|<collection>` |
| `PEER_REPLY` | A node applied a peer's request and has not written the reply yet. The only point that can `DROP` | the request type, e.g. `COMMIT_TX` |

| Action | Effect |
|---|---|
| `PAUSE` | Parks the thread until `RELEASE_FAULT`, for at most 120 seconds |
| `HALT` | Stops the process at that statement with exit status `137`, skipping shutdown hooks, like `kill -9` |
| `DROP` | Skips writing the reply (`PEER_REPLY` only) |

#### `ARM_FAULT` (admin only, testing only)
`match` is optional; without it any subject fires the fault. Arming a point that is already armed releases the previous arm first.
```json
{"type":"ARM_FAULT","point":"PEER_REPLY","action":"DROP","match":"COMMIT_TX"}
```

#### `AWAIT_FAULT` (admin only, testing only)
Waits until the armed point has fired, up to `timeoutMs` (1–120000). Answers `408-3` if it did not, or if the point is not armed.
```json
{"type":"AWAIT_FAULT","point":"WRITE_OWNERSHIP_RECHECKED","timeoutMs":30000}
```

#### `RELEASE_FAULT` (admin only, testing only)
Lets a paused thread continue and disarms the point. Without `point` it does so for every point.
```json
{"type":"RELEASE_FAULT","point":"WRITE_OWNERSHIP_RECHECKED"}
```

- **Errors**: `403-3` fault injection is disabled, `403-1` not an admin, `400-1` an unknown point or action, `DROP` on a point other than `PEER_REPLY`, or a `timeoutMs` out of range, `408-3` the point was not reached in time.

### Error codes

Every error response includes an `errorCode` field. Codes follow the pattern `NNN-N` (HTTP-style range + sequential number). The `message` field may contain additional context (e.g. the offending id or field name) appended to the default text below.

| Code | `status` | Default message |
|---|---|---|
| `400-1` | `ERROR` | *(validation message from the request validator)* |
| `400-2` | `ERROR` | Entry size exceeds maximum allowed size |
| `400-3` | `ERROR` | Duplicate `_id` in bulk save request |
| `400-4` | `ERROR` | Cannot delete the last admin user |
| `400-5` | `ERROR` | Cannot demote the last admin user |
| `400-6` | `ERROR` | Current password is incorrect |
| `400-7` | `ERROR` | Document does not comply with the collection schema |
| `400-8` | `ERROR` | The provided JSON schema is not valid |
| `400-9` | `ERROR` | Script execution failed |
| `400-10` | `ERROR` | Script exceeds the maximum allowed size |
| `400-11` | `ERROR` | Script exceeded a sandbox limit |
| `400-12` | `ERROR` | Script exceeded its memory budget |
| `400-13` | `ERROR` | The procedure source could not be parsed |
| `400-14` | `ERROR` | The trigger definition is not valid |
| `400-15` | `ERROR` | Script result exceeds the maximum allowed size |
| `400-16` | `ERROR` | The schedule definition is not valid |
| `400-17` | `ERROR` | The database already has the maximum number of schedules |
| `400-18` | `ERROR` | A saved procedure imports a `procedures/<name>` that does not exist or is disabled |
| `400-19` | `ERROR` | A `SCRIPT` operator is not allowed in a `LISTEN` pipeline |
| `400-20` | `ERROR` | The script's result promise never settled |
| `400-21` | `ERROR` | A before trigger rejected this write |
| `400-22` | `ERROR` | The request line exceeds the size limit (`maxRequestSize`, or 64Kb before authentication); sent as a `CLOSE_CONNECTION` frame and the connection is closed |
| `400-23` | `ERROR` | Schema validation exceeded its evaluation budget |
| `400-24` | `ERROR` | The schema expands past the complexity limit |
| `400-25` | `ERROR` | Too many listen registrations — the connection already holds `maxListensPerConnection`, or the server `maxListens` |
| `400-26` | `ERROR` | The transaction would exceed its size limit (`transactionMaxOps` or `transactionMaxBytes`); the write is refused and the transaction stays usable for `ROLLBACK_TRANSACTION` |
| `400-27` | `ERROR` | The filter has more operands than allowed — an `IN`/`NOT_IN` list, a `within` polygon, one conjunction's operands, one `FILTER` step's operator nodes or one `MAP` step's operators and operands past `maxFilterOperands` |
| `400-28` | `ERROR` | A MAP result exceeds the maximum entry size — a `CONCAT` built a string longer than `maxEntrySize`, one `MAP` step added more than `maxEntrySize` bytes of strings or `SCRIPT` values to a document, or one `JOIN` row's joined array is larger than `maxEntrySize` |
| `400-29` | `ERROR` | The schedule timeout exceeds the allowed maximum (`scheduleMaxTimeoutMs`) |
| `400-30` | `ERROR` | A script array exceeds the maximum length — an array-like `length` past `scriptMaxArrayLength` reached `apply`, `Reflect.apply`/`construct`, `Vector.from` or `Array.fromAsync` |
| `400-31` | `ERROR` | A schema regular expression exceeds the maximum length — a `pattern` or `patternProperties` key longer than `schemaMaxPatternLength`, refused by `SAVE_SCHEMA` |
| `400-32` | `ERROR` | The collection has the maximum number of triggers (`triggerMaxPerCollection`); replacing an existing trigger is never refused |
| `400-33` | `ERROR` | The user has the maximum number of schedules across every database (`scheduleMaxTotalPerUser`) |
| `400-34` | `ERROR` | The database has the maximum number of procedures (`maxProceduresPerDatabase`); replacing an existing procedure is never refused |
| `400-35` | `ERROR` | The schedule interval is below the allowed minimum (`scheduleMinIntervalMs`) |
| `400-36` | `ERROR` | The aggregation result exceeds the maximum result size — the `MAP` strings and `SCRIPT` values, `JOIN` arrays and rows one pipeline built passed `aggregateMaxResultBytes` |
| `400-37` | `ERROR` | The `LISTEN` request line exceeds `listenMaxRequestBytes`; refused before the query runs |
| `400-38` | `ERROR` | The user already holds `maxListensPerUser` listen registrations across their connections |
| `400-39` | `ERROR` | The schema has more regular expressions than allowed — more `pattern` values and `patternProperties` keys than `schemaMaxPatterns`, refused by `SAVE_SCHEMA` before compiling any |
| `400-40` | `ERROR` | The database has the maximum number of collections (`maxCollectionsPerDatabase`); re-creating an existing collection is never refused |
| `400-41` | `ERROR` | The server has the maximum number of databases (`maxDatabases`) |
| `400-42` | `ERROR` | The collection has the maximum number of indexes (`maxIndexesPerCollection`); re-creating an existing index is never refused |
| `401-1` | `UNAUTHENTICATED` | Must authenticate first |
| `401-2` | `UNAUTHENTICATED` | User no longer exists |
| `401-3` | `ERROR` | The user doesn't exist or the wrong credentials have been provided |
| `401-4` | `UNAUTHENTICATED` | Too many failed authentication attempts on this connection — the server closes the connection after sending it |
| `403-1` | `FORBIDDEN` | Action is forbidden, no permissions |
| `403-2` | `FORBIDDEN` | Script execution is disabled on this server |
| `403-3` | `FORBIDDEN` | Fault injection is disabled on this server — a [fault injection](#fault-injection-testing-and-debugging-only) operation on a server without `faultInjectionEnabled=true` |
| `403-4` | `FORBIDDEN` | Procedure is bound by a trigger or schedule that runs as another user — a [`SAVE_PROCEDURE`](#save_procedure) or [`DELETE_PROCEDURE`](#delete_procedure) by someone other than the binding's definer, a database owner or an admin |
| `403-5` | `FORBIDDEN` | The trigger or schedule belongs to another user — a `SAVE_TRIGGER`, `DELETE_TRIGGER`, `SAVE_SCHEDULE` or `DELETE_SCHEDULE` replacing or removing a definition by someone other than its definer, a database owner or an admin |
| `403-6` | `FORBIDDEN` | Procedure source requires script MANAGE — a [`LIST_PROCEDURES`](#list_procedures) with `includeSource: true`, or a [`LIST_SCHEDULES`](#list_schedules) with `includeArgs: true`, by a caller with `READ` but not script `MANAGE`, ownership or admin |
| `404-1` | `NOT_FOUND` | User not found |
| `404-2` | `NOT_FOUND` | Entry not found |
| `404-3` | `NOT_FOUND` | No results |
| `404-4` | `NOT_FOUND` | Database not found |
| `404-5` | `NOT_FOUND` | No users found |
| `404-6` | `NOT_FOUND` | No index registered for the specified field |
| `404-7` | `NOT_FOUND` | Listen registration not found |
| `404-8` | `NOT_FOUND` | Procedure not found |
| `404-9` | `NOT_FOUND` | Trigger not found |
| `404-11` | `NOT_FOUND` | Collection not found — a write (`SAVE`, `BULK_SAVE`, `DELETE`) named a collection this node has no metadata for. Definitive only when clustering is off; a clustered node answers `503-10` instead, since it cannot tell "never existed" from "not replicated here yet" |
| `404-12` | `NOT_FOUND` | The collection has no schema — a [`GET_SCHEMA`](#get_schema) on a collection without one |
| `408-1` | `ERROR` | Script exceeded its time budget |
| `408-2` | `ERROR` | Script was cancelled |
| `408-3` | `ERROR` | The armed fault point was not reached in time (`AWAIT_FAULT`, testing only) |
| `408-4` | `ERROR` | The connection did not authenticate within `authTimeoutMs`; sent as a `CLOSE_CONNECTION` frame and the connection is closed |
| `408-5` | `ERROR` | The transaction was rolled back after reaching `transactionMaxAgeMs`, however active it was; answered to the next request |
| `408-6` | `ERROR` | The aggregation exceeded its time budget (`aggregateTimeoutMs`) |
| `409-1` | `ERROR` | User already exists |
| `409-2` | `ERROR` | Database already exists |
| `409-3` | `ERROR` | A transaction is already in progress for this connection |
| `409-4` | `ERROR` | No active transaction for this connection |
| `409-5` | `ERROR` | Could not acquire the collection lock in time; transaction aborted |
| `409-6` | `ERROR` | Operation not allowed while a transaction is open |
| `409-7` | `ERROR` | Transaction aborted: a participant could not prepare |
| `409-8` | `ERROR` | The procedure, trigger or schedule was modified by someone else |
| `409-9` | `ERROR` | The transaction was aborted and must be rolled back before continuing |
| `409-11` | `ERROR` | A name that differs only by case already exists and would share storage with it |
| `409-12` | `ERROR` | A previous transaction on this connection is still being resolved on a participant; retry once it finishes |
| `409-13` | `ERROR` | A participant no longer holds this transaction's buffered writes (it restarted or reaped the session); roll back and retry |
| `409-14` | `ERROR` | The transaction already committed on its owner; it can no longer be rolled back |
| `409-15` | `ERROR` | The primary-key index cannot be rebuilt from the page files |
| `409-16` | `ERROR` | The connection's transaction was rolled back after `transactionIdleTimeoutMs` without a request; answered once, to the next request, which is not executed |
| `410-1` | `NOT_FOUND` | The listen ended: a collection it reads was dropped, its database was dropped, or the listening user can no longer read it. Pushed once on the listen's connection; the `listenId` is unregistered afterwards |
| `413-1` | `ERROR` | The connection left more than `clientOutboundQueueMaxBytes` of responses unread; its backlog is dropped, its listens end, and this `CLOSE_CONNECTION` frame is the last thing written before the connection is closed (a reader that never catches up does not see it) |
| `500-1` | `ERROR` | Error during authentication |
| `500-2` | `ERROR` | Error creating user |
| `500-3` | `ERROR` | Error deleting user |
| `500-4` | `ERROR` | Error changing password |
| `500-5` | `ERROR` | Error changing permissions |
| `500-6` | `ERROR` | Error while saving entries |
| `500-7` | `ERROR` | Error while saving entry |
| `500-8` | `ERROR` | Error while retrieving entry |
| `500-9` | `ERROR` | Error while processing aggregation |
| `500-10` | `ERROR` | Error while deleting entry |
| `500-11` | `ERROR` | Error while creating database |
| `500-12` | `ERROR` | Error updating database owners |
| `500-13` | `ERROR` | Error while dropping database |
| `500-14` | `ERROR` | Error while listing databases |
| `500-15` | `ERROR` | Error while creating collection |
| `500-16` | `ERROR` | Error while dropping collection |
| `500-17` | `ERROR` | Error while listing collections |
| `500-18` | `ERROR` | Error while creating index |
| `500-19` | `ERROR` | Error listing users |
| `500-20` | `ERROR` | Error while dropping index |
| `500-21` | `ERROR` | Error while reindexing |
| `500-22` | `ERROR` | Error while gathering database stats |
| `500-23` | `ERROR` | Error while processing listen operation |
| `500-24` | `ERROR` | Error while processing transaction operation |
| `500-25` | `ERROR` | Error while saving collection schema |
| `500-26` | `ERROR` | Error while deleting collection schema |
| `500-27` | `ERROR` | Error while saving the procedure |
| `500-28` | `ERROR` | Error while deleting the procedure |
| `500-29` | `ERROR` | Error while saving the trigger |
| `500-30` | `ERROR` | Error while deleting the trigger |
| `500-31` | `ERROR` | Error while saving the schedule |
| `500-32` | `ERROR` | Error while deleting the schedule |
| `421-1` | `ERROR` | This node is not the owner of the target collection |
| `421-3` | `ERROR` | This read joins collections the transaction wrote on different nodes |
| `503-1` | `ERROR` | Max number of connections reached; sent as a `CLOSE_CONNECTION` frame and the connection is closed (with `tlsEnabled=true` it is closed before its handshake, with no frame) |
| `503-2` | `ERROR` | Cluster does not have a write quorum |
| `503-3` | `ERROR` | Timed out waiting for the replication quorum *(the local commit **stands** and anti-entropy reconciles the lagging replicas — the write happened, so this is not a "it did not apply" error; blind retries are safe for `SAVE`/`DELETE`, which are idempotent, but not for a read-modify-write script)* |
| `503-4` | `ERROR` | The collection's owner node is unreachable |
| `503-5` | `ERROR` | Admin coordinator is synchronizing, retry shortly |
| `503-6` | `ERROR` | Too many scripts running, retry shortly *(the message names the scope that refused it: `node`, `user` or `database`)* |
| `503-7` | `ERROR` | The node the script was placed on did not report an outcome; it was not run again in case it already had *(only raised when a placed script's outcome could not be established; it is never re-run locally, so whether to retry is the caller's decision)* |
| `503-8` | `ERROR` | The collection's owner did not report an outcome; the write may already have been applied, so retrying is only safe for an idempotent write *(distinct from `503-4`, which means the owner was provably never reached)*. Also the answer when the collection's ownership moved after the write was applied on this node, since `421-1` would claim it was not |
| `503-9` | `ERROR` | The admin coordinator could not be resolved, retry shortly *(a coordinated admin op is never applied locally when no coordinator can be reached: an unreplicated DDL answered with `OK` would diverge the cluster silently)* |
| `503-10` | `ERROR` | The collection has not reached this node yet, retry shortly *(a write routed to the collection's owner can arrive before the `CREATE_COLLECTION` that created it, since admin replication only waits for a quorum and the owner need not be in it — retryable, not a server fault)* |
| `503-13` | `ERROR` | The server is shutting down, retry against another node or later *(answered to every request on an already-open connection once shutdown has begun, except `ROLLBACK_TRANSACTION` and `CLOSE_CONNECTION`; a write acknowledged before this point is indexed and durable, and one refused with it was not applied)* |
| `503-14` | `ERROR` | Too many unauthenticated connections from this address (`maxUnauthenticatedConnectionsPerAddress`); sent as a `CLOSE_CONNECTION` frame and the connection is closed (with `tlsEnabled=true` it is closed before its handshake, with no frame) |
| `503-15` | `ERROR` | The server's listen registrations already keep `listenMaxRetainedBytes` of requests; retry once some end |
| `503-16` | `ERROR` | The background index queue is full and nothing was written; retry shortly *(a `SAVE`, `BULK_SAVE`, `DELETE` or `COMMIT_TRANSACTION` that found `backgroundQueueMaxEvents` events queued and no room within `backgroundBacklogWaitMs`; a write forwarded to a full owner gets it at once. Writes inside a transaction are buffered and enqueue nothing, so only their commit is held back, and the transaction stays open)* |

### Running the server

The server ships **without** an admin password: neither the bundled defaults nor the tracked `lwnrdb.cfg` set
`defaultAdminPassword`, so a first start with no password refuses to come up (see [Bootstrap](#bootstrap)). Put one
in the `lwnrdb.cfg` of the working directory before the first start:

```bash
mvn clean package -DskipTests
echo "defaultAdminPassword=<choose-a-strong-password>" >> lwnrdb.cfg
java -XX:+UseZGC -jar target/lwnrdb-1.0-SNAPSHOT.jar
```

With Docker, the image copies the credential-free `lwnrdb.cfg`, so a container started without a mounted
configuration stops at startup with the `FATAL` message instead of coming up with a known password. Mount your own
`lwnrdb.cfg` over it; that is also how the TLS and cluster secrets are supplied:

```bash
docker build -t lwnrdb .
docker run -p 8989:8989 -v ./db:/app/db -v ./lwnrdb.cfg:/app/lwnrdb.cfg:ro lwnrdb
```

The image runs as the unprivileged `lwnrdb` user, not root. A host directory mounted over `/app/db`, `/app/logs` or
`/app/certs` must be writable by that user's uid (`docker run --rm --entrypoint id lwnrdb` prints it).

The integration suites in `test_utils/` authenticate with `base_utils.PASSWORD` (`Tst-Admin-9xQ2mV`): a server the
suites do not start themselves must have `defaultAdminPassword=Tst-Admin-9xQ2mV` in its `lwnrdb.cfg`, which is what
CI appends before starting it. The suites that start their own servers write that value into their own config.

### Client (shell, exec, dump/restore)

The server jar is also its own client. When the first argument starts with `--`, it connects to a running server,
local or remote, instead of starting one:

```bash
java -jar lwnrdb.jar --shell   [connection]
java -jar lwnrdb.jar --exec '<json>' [--exec '<json>' ...] [connection]
java -jar lwnrdb.jar --exec [connection] < commands.jsonl
java -jar lwnrdb.jar --dump [--databases a,b | --collections db.coll,...] [--compress] [connection] > backup.ldump
java -jar lwnrdb.jar --restore [connection] < backup.ldump
```

| Connection flag | Default | |
|---|---|---|
| `--host <host>` | `127.0.0.1` | |
| `--port <port>` | `8989` | |
| `--user <user>` | `admin` | |
| `--password <password>` | | or the `LWNRDB_PASSWORD` environment variable; `--shell` on a terminal prompts for it |
| `--tls` | off | TLS, verified against the JVM trust store with hostname verification |
| `--insecure` | off | with `--tls`, trust any certificate, e.g. the server's dev-only self-signed one |

- **`--shell`** prints a `lwnrdb> ` prompt, sends each line (one JSON request) as it is and pretty-prints the reply.
  `exit`, `quit` or end of input ends the session. There is no line editing, history or multi-line input.
- **`--exec`** sends each `--exec` value, or each line of standard input when no value is given, and prints every
  reply on its own line. It stops at the first reply that is not `OK`.
- **`--dump`** writes a backup to standard output and progress (`shop.orders: 12000 documents`) to standard error.
  `--compress` gzips the stream.
- **`--restore`** reads a backup from standard input, plain or gzipped (detected by its first two bytes), and prints
  `restored N requests` to standard error every 1000 requests and at the end.

Exit codes: `0` success; `1` a command answered non-OK, or the dump or restore stopped (the restore names the line);
`2` usage error; `3` the server cannot be reached, TLS failed, or authentication was refused. The client never reads
`lwnrdb.cfg` and never creates `db/` or `logs/` in its working directory.

**The dump format is the restore script.** Every line is one ordinary request: `CREATE_DATABASE`,
`CREATE_COLLECTION`, `BULK_SAVE` (at most 4 MB of documents per line), `SAVE_SCHEMA`, `CREATE_INDEX`,
`SAVE_PROCEDURE`, `SAVE_TRIGGER` and `SAVE_SCHEDULE`, in that order, so a restore writes documents before the schema,
before hooks and indexes that would re-check or slow them, saves a procedure before what imports or binds it, and
installs after triggers last so they do not fire on a fresh target. Restore sends each line and stops at the first
error; the lines before it stay applied.

Every request runs as `--user`, so what a dump or restore can do follows that user's permissions:

| User | Dump | Restore into an existing database |
|---|---|---|
| Admin | everything | everything |
| Owner of the database | `--databases db` or `--collections db.x`, definitions included | yes, definitions included |
| `READ` on the database, no script `MANAGE` | data, schemas and indexes; definitions are skipped with a note | no, writes need `READ_WRITE` |
| `READ_WRITE` and script `MANAGE` | everything in the database | data yes; a procedure, trigger or schedule another user defined or bound stops it (`403-4`/`403-5`) |
| A grant on single collections only | no, listing a database's collections needs database `READ` | no |

A dump skips a kind of definition it may not list (`403-2` when scripts are disabled, `403-6` without script
`MANAGE`) and says so on standard error; when the procedures are skipped, the triggers bound to them are skipped too.
A dump without `--databases` skips, with a note, a database the user can see but not read; a database named in
`--databases` that the user cannot read stops the dump. Restoring a database that does not exist yet needs the
global `CREATE_DATABASE` permission, and the user who creates it becomes its owner.

A dump covers the whole server, the databases named by `--databases`, or the collections named by `--collections`
(each `db.coll`). A collection dump carries `CREATE_DATABASE`, `CREATE_COLLECTION`, the collection's documents,
schema and indexes, its triggers, and the procedures those triggers call together with everything they import; it
carries no schedules, which belong to the database.

| Not in a dump | Why |
|---|---|
| Users, permissions, database owners | Password hashes never leave the server, and `CREATE_USER` takes only plaintext |
| `script_runs` | Reserved: client writes to it are refused |
| `admin` and cluster metadata | Not user data |
| Trigger runs, in-doubt transactions | Node-local runtime state |
| Definers, versions, timestamps, `disabledReason` | Restored definitions are stamped by the restoring user, who becomes their definer and whose permissions they then run with, and start at version 1; a disabled one comes back with `enabled:false` |

**A dump is not a point-in-time snapshot.** Collections are read page by page while writes may continue; for an exact
copy, stop the writers while dumping.

**Restore merges** into whatever the target already holds: an existing database or collection is reused (restore
lists the databases first and skips the `CREATE_DATABASE` of one that exists, reporting `restored N requests, skipped
M existing databases`; a `409-2` answer is still accepted), documents with the same `_id` and definitions with the same name are
overwritten, and nothing the dump lacks is deleted. Restoring the same dump twice leaves the same data. On a merge,
the target's own schema, before hooks and after triggers apply to the restored documents: a schema the dumped
documents violate stops the restore with `400-7`, a before hook can veto or rewrite them, and enabled after triggers
fire. Restore into a collection without them, or disable them first. To replace rather than merge, drop first:

```bash
java -jar lwnrdb.jar --exec '{"type":"DROP_COLLECTION","databaseName":"shop","collectionName":"orders"}' --password …
java -jar lwnrdb.jar --restore --password … < orders.ldump
```

From Docker, override the command (the image's working directory holds its own `lwnrdb.cfg`, which the client
ignores); add `-i` to feed a restore:

```bash
docker run --rm lwnrdb java -jar app.jar --dump --compress --host db.internal --password … > backup.ldump.gz
docker run --rm -i lwnrdb java -jar app.jar --restore --host db.internal --password … < backup.ldump.gz
```

### Bootstrap

On first startup, if no admin user exists, the server creates a superadmin from `defaultAdminUsername` / `defaultAdminPassword`. At that moment the password is **required**: at least 8 characters, and not `administrator`. The server refuses to start (one `FATAL` log line, non-zero exit, no port opened):

- while `defaultAdminPassword` is the old well-known value `administrator` (in any letter case), **whether or not an admin already exists**. Edit `lwnrdb.cfg` and restart;
- while `defaultAdminPassword` is on a short list of the most commonly used passwords (`password123`, `changeme`, `12345678`, `qwertyuiop`, `admin1234`, …, compared ignoring case) or equals `defaultAdminUsername` ignoring case, also whether or not an admin already exists;
- while `defaultAdminPassword` is blank or missing **and** no admin user exists yet, since there would be no way to bootstrap one.

Once an admin exists, `defaultAdminUsername` / `defaultAdminPassword` are ignored and the password key may be removed from `lwnrdb.cfg`. The check reads only the configured value, never the stored hash, so a deployment whose admin was created with `administrator` and whose config no longer says so starts normally: **change that admin's password with [`SET_PASSWORD`](#set_password)**, because the server cannot tell.

### Configuration

Configuration is read from `lwnrdb.cfg` in the working directory, falling back to bundled defaults for any missing key. Lines starting with `#` and blank lines are ignored, so you can document special cases inline.

Every value is **validated at startup**. If any value is invalid, the server logs a `FATAL` error listing all problems and refuses to start. A whole-number key that the server holds as a 32-bit integer refuses anything past `2147483647`, and a size whose unit would overflow 64 bits (`9000000000tb`) is refused rather than wrapped.

**`lwnrdb.cfg` keys:**

| Key | Rule |
|---|---|
| `port` | Valid number, 1–65535 |
| `bindAddress` | Interface the client port binds to: an IP literal or a host name, checked at startup. Blank (the default) binds every interface; `127.0.0.1` accepts only local clients. If the port cannot be bound the server exits with status `1` |
| `maxConnections` | Valid number ≥ 0. `0` means unlimited connections. Counts client sockets only. Like every protective limit where `0` turns the limit off (`maxUnauthenticatedConnectionsPerAddress`, `maxUnauthenticatedInvalidRequests`, `maxFailedAuthAttempts`, `authFailureDelayBaseMs`, `transactionMaxOps`, `transactionMaxAgeMs`, `maxListensPerConnection`, `maxListens`, `maxListensPerUser`, `maxFilterOperands`, `aggregateTimeoutMs`, `maxConcurrentScripts`, `maxConcurrentScriptsPerUser`, `maxConcurrentScriptsPerDatabase`), a `0` here is named in one `SECURITY WARNING` at startup |
| `maxFailedAuthAttempts` | Valid number ≥ 0 (default `5`). Failed `AUTHENTICATE`s one connection may make; the last one answers `401-4` and closes the connection. Never reset by a success. `0` disables the cap (the delay still applies) |
| `authFailureDelayBaseMs` | Valid number ≥ 0 (default `250`). Delay before the next `AUTHENTICATE` for a username from the same address after a failure, doubled per consecutive failure. `0` disables the delay |
| `authFailureDelayMaxMs` | Valid number ≥ 0 and ≥ `authFailureDelayBaseMs` (default `30000`). Cap on that delay |
| `maxConcurrentPasswordChecks` | Valid number ≥ 0 (default `0`, one per available processor). Password hashes allowed to run at once |
| `maxRequestSize` | Human-readable size (default `64Mb`), at least `maxEntrySize` and at most `1Gb`. Largest request line an authenticated connection may send; a longer one is answered `400-22` and the connection closed. Before authenticating a line is capped at 64Kb whatever this says |
| `clientOutboundQueueMaxBytes` | Human-readable size (default `16Mb`). Largest unread backlog of responses and LISTEN updates one connection may hold, counted in the UTF-8 bytes they take on the wire (line terminators included, so a CJK character counts 3); past it the connection's listens end, `413-1` is queued and the connection is closed. A single larger response is still delivered when nothing waits behind it |
| `authTimeoutMs` | Valid number ≥ 1 (default `10000`). Milliseconds a new connection has to authenticate, counted from accept and never extended; then `408-4` and the connection is closed |
| `maxListensPerUser` | Valid number ≥ 0 (default `256`). `LISTEN` registrations one user may hold across all of their connections; past it, `400-38`. `0` means unlimited and is warned at startup |
| `listenMaxRequestBytes` | Human-readable size (default `1Mb`). Largest `LISTEN` request line; a longer one is answered `400-37` before it runs |
| `listenMaxRetainedBytes` | Human-readable size (default `64Mb`). Total serialized length of the requests every `LISTEN` registration on the server keeps; past it, `503-15` |
| `listenRerunTimeoutMs` | Valid number ≥ 1 (default `10000`). Wall-clock budget of one background `LISTEN` re-run; past it the re-run is dropped with a warning and nothing is pushed |
| `transactionIdleTimeoutMs` | Valid number ≥ 1 (default `30000`). Milliseconds a connection with an open transaction may stay silent before the transaction is rolled back and its locks released; the next request answers `409-16` once |
| `filePath` | Path that exists or can be created, and is writable by the process. Created owner-only (`0700`) when missing; see [File permissions](#file-permissions) |
| `logPath` | Path that exists or can be created, and is writable by the process. Created owner-only (`0700`) when missing. Log cleanup deletes only expired files named `yyyy-MM-dd.log` here, so pointing it at a populated folder (even `.`) deletes nothing else |
| `backgroundProcessingThreads` | Valid number ≥ 1 |
| `backgroundQueueMaxEvents` | Valid number ≥ 1 (default `200000`). Bound on the background index/admin queue, counted in events (a bulk write is one event). A `SAVE`, `BULK_SAVE`, `DELETE` or `COMMIT_TRANSACTION` arriving while it is reached waits for room, holding no lock, then answers `503-16` with nothing written. Acknowledged writes are never dropped and replicated writes are never held back |
| `backgroundBacklogWaitMs` | Valid number ≥ 0 (default `5000`). Milliseconds such a write waits for room before `503-16`; `0` refuses at once |
| `maxLogFiles` | Valid number ≥ 1 |
| `maxPageSize` | Human-readable size (e.g. `2Mb`) > 0, and strictly greater than `maxEntrySize` |
| `maxEntrySize` | Human-readable size (e.g. `1Mb`) > 0, and strictly smaller than `maxPageSize` |
| `syncWrites` | `true` or `false` (default `true`). Sync every write's record in the collection's write-ahead log to the device before acknowledging it, so an acknowledged write survives a power loss (see [Durability](#durability)). `false` acknowledges once the OS has the write and logs a startup DURABILITY WARNING. With `true`, a platform that cannot sync a directory refuses to start |
| `walCheckpointBytes` | Size (default `8Mb`). Once a collection's write-ahead log passes it, the pages and `pk.idx` its records touched are synced together and the log is rewritten as a single checkpoint. Larger means fewer checkpoints and a longer replay after a crash |
| `schemaValidationBudget` | Valid number ≥ 1000 (default `5000000`). Evaluation steps one write request may spend against its collection's JSON Schema (one per schema node evaluated, one per regex matcher step), shared by every document of a `BULK_SAVE`; a request that exhausts it is refused with `400-23`. A large `BULK_SAVE` against an expensive schema may need it raised. The check runs before the collection lock and is reused under it when the schema has not changed; when it has, validation runs again under the collection write lock, so raising it raises the worst-case time one write holds that lock |
| `schemaMaxPatternLength` | Valid number > 0 (default `4096`). Longest `pattern` value or `patternProperties` key, in characters, that `SAVE_SCHEMA` accepts; a longer one is refused with `400-31`. A schema stored before the limit still loads. Keep it equal on every node of a cluster, since the node that runs `SAVE_SCHEMA` decides |
| `schemaMaxPatterns` | Valid number > 0 (default `256`). Most regular expressions (`pattern` values plus `patternProperties` keys, counted through `$ref`s) one schema may hold; `SAVE_SCHEMA` refuses more with `400-39` before compiling any. A stored or replicated schema with more still loads, with a warning. Keep it equal on every node of a cluster |
| `schemaPatternCacheMaxBytes` | Valid size > 0 (default `32Mb`). Estimated heap the node's compiled schema regular expressions may keep cached, weighed by their character sets, nodes and literal text; the least recently used are evicted past it. A pattern longer than `schemaMaxPatternLength` or heavier than the whole cache is compiled on every use and never cached |
| `defaultAdminUsername` | 3–64 characters from `A-Z a-z 0-9 _ -`, the same rule `CREATE_USER` applies (cluster peers refuse to replicate any other user name). The server refuses to start otherwise |
| `defaultAdminPassword` | Required (at least 8 characters, not `administrator`) when no admin user exists; may be blank once one does. `administrator`, a commonly used password and the admin username itself (all ignoring case) are always refused at startup, and a non-blank value shorter than 8 characters is a validation error |
| `maxMemory` | Human-readable size; `0` (unlimited, named in the startup `SECURITY WARNING`) and `-1` (caching disabled) are also valid |
| `transientLoadPermits` | Valid number ≥ 1 (default `4`). Whole `pk.idx` or field-index loads that may not be cached, allowed to run at once: one whose upper-bound estimate from the file lengths (`pk.idx`, or an `.idx` plus its `.idxlog`) does not fit in `maxMemory`, every load while caching is disabled, and every load by a lock-free reader. Further ones wait; nothing is refused |
| `transactionLockTimeoutMs` | Valid number ≥ 1. Milliseconds a write inside a transaction waits to acquire a busy collection's write lock before the transaction is aborted (`409-5`) |
| `deadEvictionMs` | Valid number ≥ 1, and greater than `deadTimeoutMs`. Milliseconds a node must stay DEAD before it is dropped from the membership view. Until then it still counts towards the quorum denominator, so this must comfortably exceed any outage a node is expected to return from |
| `deadProbeIntervalMs` | Valid number ≥ 1 (default `10000`), and not smaller than `gossipIntervalMs`. How often a peer this node sees as `SUSPECT` or `DEAD` is probed with a gossip message. This is how two sides of a partition that each marked the other `DEAD` find each other again once it heals |
| `shutdownTimeoutMs` | Valid number ≥ 1 (default `15000`). Total budget for a graceful shutdown — refusing new connections, releasing open transactions, draining the trigger and background-index queues. Work still outstanding when it expires is abandoned with a warning naming what was dropped |
| `tlsEnabled` | `true` or `false`. When `true`, every connection is encrypted and plaintext clients are rejected. When `false` (the default), a `SECURITY WARNING` is logged at every startup |
| `tlsKeystorePath` | Path to a PKCS12 keystore. Used when `tlsEnabled=true` or when `clusterEnabled` and `clusterTlsEnabled` are both `true`; its parent directory must then be writable. If the file is absent a self-signed keystore is generated there, owner-only (`0600`, in a folder created `0700`). When an existing keystore is loaded, a certificate that has expired or expires within 30 days is named in a `SECURITY WARNING` |
| `tlsKeystorePassword` | Non-blank string protecting the PKCS12 keystore. Required whenever the keystore is used (`tlsEnabled=true`, or cluster TLS). While it is the shipped `change_it`, a `SECURITY WARNING` is logged at every startup that uses the keystore |
| `clusterEnabled` | `true` or `false`. Master switch for multi-node clustering. When `false` the node runs standalone (default) |
| `clusterPort` | Valid number 1–65535, different from `port`. Node-to-node channel |
| `clusterBindAddress` | Interface the cluster server binds to |
| `clusterAdvertisedAddress` | Non-blank address other nodes use to reach this node |
| `clusterSeeds` | Comma-separated `host:port` seeds to join through (empty on the first node) |
| `nodeId` | Stable node id; empty = auto-generate and persist under `filePath/cluster/node.id` |
| `clusterExpectedSize` | Valid number ≥ 1. Baseline for the write-quorum majority until membership stabilizes |
| `gossipIntervalMs` | Valid number ≥ 1. Gossip/heartbeat cadence |
| `suspectTimeoutMs` | Valid number ≥ 1. Silence before a node is marked SUSPECT |
| `deadTimeoutMs` | Valid number ≥ 1, greater than `suspectTimeoutMs`. Silence before a node is marked DEAD |
| `replicationAckTimeoutMs` | Valid number ≥ 1. Max wait for the replication quorum |
| `virtualNodesPerNode` | Valid number ≥ 1. Virtual nodes per node on the consistent-hash ring |
| `readFallbackToLocal` | `true` or `false`. Serve reads from the local replica when the owner is unreachable |
| `scriptRoutingEnabled` | `true` or `false` (default `true`). Whether a script ([`RUN_SCRIPT`](#run_script), [`CALL_PROCEDURE`](#call_procedure)) may be placed on another live node instead of running where it arrived. Only a node that is alive **and** caught up on admin metadata is chosen. `scriptsEnabled` and the `script*` sandbox keys must be uniform across the cluster, and every node rolled, before enabling it — see [docs/clustering.md](docs/clustering.md) → *Scripts* |
| `scriptLocalityWeight` | Integer `0`–`100` (default `50`). How strongly placement prefers a node owning the scoped database's collections: score is `(weight / 100) * share - loadRatio`, higher wins, and `0` places purely by load. Only meaningful for databases with few collections. A **local** decision — it need not be uniform across the cluster |
| `clusterTlsEnabled` | `true` or `false`. TLS-encrypt the node-to-node channel (reuses the keystore). The TLS is **mutual**: every node presents the keystore's certificate and the cluster listener accepts only peers whose certificate that same keystore trusts, so all nodes must share one keystore (or one CA). A clustered node with this off logs a `SECURITY WARNING` at startup |
| `clusterSecret` | Shared secret authenticating the cluster channel, **at least 32 characters**. Required when `clusterEnabled=true`; a shorter one refuses to start. Compared in constant time, and every peer connection must present it in a leading `HELLO` frame |
| `clusterMaxFrameSize` | Human-readable size (default `256Mb`), at least twice `maxRequestSize` when clustering is on. Largest frame either side of the node-to-node channel accepts after the `HELLO` handshake; a larger one closes the connection |
| `clusterHandshakeTimeoutMs` | Valid number ≥ 1 (default `5000`). How long a new cluster connection has to send its `HELLO` frame (under a 4 KB line cap) before it is closed without a reply |
| `clusterMaxClockSkewMs` | Valid number ≥ 1 (default `3600000`, 1 hour). A replicated version, delete version, admin record version or collection incarnation more than this far ahead of the receiving node's wall clock is refused and not observed into its write clock. Keep it above the worst real clock skew between nodes |
| `antiEntropyIntervalMs` | Valid number ≥ 1. How often each node runs a background anti-entropy sweep reconciling its collections against live peers |
| `tombstoneRetentionMs` | Valid number ≥ 1. How long delete tombstones are kept before anti-entropy GC; must exceed the longest expected node downtime |
| `scriptTimeZone` | A valid IANA time zone id (e.g. `UTC`, `Europe/Madrid`) or fixed offset. The zone a stored script's `Date`/`Temporal`/`toLocaleString` answers in, so the same script returns the same answer on every node |
| `scriptLocale` | A valid BCP 47 language tag (e.g. `en-US`). The locale a stored script's locale-sensitive formatting and collation use |
| `scriptsEnabled` | `true` or `false` (default `true`). Whether clients may run scripts at all ([`RUN_SCRIPT`](#run_script)), whether stored procedures may be installed or called, and whether an `AGGREGATE` pipeline may carry a [script operator](#script-operators-simplejs-in-the-pipeline). On by default: a script is bounded by the server-fixed sandbox below and by permissions — only an admin, a database owner, or a user holding `scriptPermissions` for that database may start one, and every operation the script issues is authorized again on its own request. When `false` the operations are refused with `403-2` |
| `scriptInstructionBudget` | Valid number ≥ 1. Max interpreter instructions per script run; exceeding it aborts with `400-11` |
| `scriptTimeoutMs` | Valid number ≥ 1. Max wall-clock time per script run; exceeding it aborts with `408-1` |
| `scriptMaxDepth` | Valid number ≥ 1. Max nested call depth per script run; exceeding it aborts with `400-11` |
| `scriptMaxSourceBytes` | Human-readable size > 0. Max accepted script source; larger is rejected with `400-10` |
| `scriptMaxMemoryBytes` | Human-readable size > 0. Max bulk memory a script run may allocate; exceeding it aborts with `400-12`. Bounds the allocations that are proportional to a script-supplied length (a huge `repeat`/`padStart`, a dense array, a typed array, a `join`); ordinary small allocations are bounded by `scriptInstructionBudget` instead |
| `scriptMaxResultBytes` | Human-readable size > 0 (default `16Mb`). Max size of the value a script returns; a larger result fails the run with `400-15` (the `console` output still comes back). Use `db.cursor` to process more data than can be returned |
| `scriptMaxArrayLength` | Valid number ≥ 1 (default `16777216`). Longest array-like a script may expand in one native step (`apply` and `Reflect.apply`/`construct` argument lists, `Vector.from`, `Array.fromAsync`); a longer one fails the run with `400-30` before anything is allocated |
| `scriptCursorBatchSize` | Valid number ≥ 1 (default `500`). Default number of documents `db.cursor` fetches per batch |
| `scriptCursorMaxBatchSize` | Valid number ≥ 1 (default `5000`), and ≥ `scriptCursorBatchSize`. Upper clamp for a caller-supplied `batchSize`, so one call cannot materialise an unbounded batch |
| `aggregationScriptInstructionBudget` | Valid number ≥ 1 (default `1000000`). Max instructions **all** the [script operators](#script-operators-simplejs-in-the-pipeline) in one pipeline may execute before the query aborts with `400-11`. Per pipeline, not per document — one interpreter serves the whole query, so a runaway predicate aborts it rather than getting a fresh budget per row |
| `aggregationScriptTimeoutMs` | Valid number ≥ 1 (default `2000`). Wall clock for the same set of operators, also per pipeline; exceeding it aborts the query with `408-1` |
| `aggregationScriptMaxSourceBytes` | Human-readable size > 0 (default `16Kb`). Max source size of a single script operator; a larger one is rejected with `400-10` |
| `maxConcurrentScripts` | Valid number ≥ 0 (default `16`); `0` disables the cap. Max client-initiated script runs on this node at once; a caller refused after waiting `scriptQueueWaitMs` gets `503-6`. Triggers and schedules are bounded by their own pools instead, so the node-wide ceiling on concurrent interpreters is `maxConcurrentScripts + triggerThreads + scheduleThreads` — and that sum × `scriptMaxMemoryBytes` is the worst-case script heap, **additive** with `maxMemory` and the metadata cache budgets |
| `maxConcurrentScriptsPerUser` | Valid number ≥ 0 (default `4`); `0` disables the slice. Max script runs one user may have on this node at once — a trigger or schedule counts against its definer. Checked before the node-wide wait and never waits itself: a caller at its own ceiling gets `503-6` naming `user` at once. A trigger refused here is retried after `triggerRetryBackoffMs` without consuming an attempt; a scheduled run refused here skips that occurrence |
| `maxConcurrentScriptsPerDatabase` | Valid number ≥ 0 (default `8`); `0` disables the slice. The same bound per database, `503-6` naming `database`. From `2` up, the last slot is kept for the database's owners and admins running `RUN_SCRIPT`/`CALL_PROCEDURE`, so other users, triggers and schedules stop one short of it |
| `scriptQueueWaitMs` | Valid number ≥ 0 (default `250`); `0` rejects immediately. How long a run waits for a permit before `503-6`. The wait absorbs bursts so a short spike queues instead of erroring; it may legally exceed `scriptTimeoutMs` |
| `scriptMaxLogLines` | Valid number ≥ 1. Max `console` lines returned with the response (newest kept) |
| `scriptMaxLogLineChars` | Valid number ≥ 1. Max characters kept per returned `console` line |
| `scriptTextImportEnabled` | `true` or `false` (default `false`). Whether a script may evaluate a string as a module through the `script` module's `importText` |
| `scriptProcedureImportEnabled` | `true` or `false` (default `true`). Whether a script may import a stored procedure of its own database as a module with `import … from "procedures/<name>"`. On by default, unlike `scriptTextImportEnabled`: `importText` evaluates a caller-supplied string, whereas a procedure import evaluates code only `SAVE_PROCEDURE` could have installed |
| `procedureCacheSize` | Compiled stored procedures retained per node (>= 0, default `128`); keyed by procedure version, so a save can never serve a stale entry. `0` compiles on every call |
| `procedureCacheMaxBytes` | Valid size > 0 (default `16Mb`). Estimated heap the compiled-program caches (stored procedures and parsed `RUN_SCRIPT` programs) may hold, counted from each program's source length; whichever of this and `procedureCacheSize` is reached first evicts the least recently used program, and a program larger than the whole budget is compiled but not kept |
| `maxProceduresPerDatabase` | Valid number ≥ 1 (default `500`). A `SAVE_PROCEDURE` creating one more is refused with `400-34`; replacing an existing one is always allowed |
| `maxCollectionsPerDatabase` | Valid number ≥ 1 (default `1000`). A `CREATE_COLLECTION` creating one more in the database is refused with `400-40`; re-creating an existing collection is always allowed. Bounds admin-metadata growth, which stays in memory regardless of `maxMemory` |
| `maxDatabases` | Valid number ≥ 1 (default `1000`). A `CREATE_DATABASE` creating one more user database is refused with `400-41` |
| `maxIndexesPerCollection` | Valid number ≥ 1 (default `64`). A `CREATE_INDEX` creating one more field index on the collection is refused with `400-42`; re-creating an existing index is always allowed. Bounds the per-write index-maintenance cost |
| `metadataCacheMaxBytes` | Human-readable size > 0 (default `72Mb`). One memory bound shared by the byte-budgeted metadata caches: stored-procedure sources, collection schemas and schedule definitions. Separate from `maxMemory`, which bounds the user document/index cache only |
| `metadataCacheMaxEntries` | Entries in the count-budgeted metadata caches (>= 0, default `4096`): the per-collection trigger lists, and the remembered "no such procedure/schema" answers. Bounded by count, not bytes — both are small and read on a hot path. `0` reads from disk every time |
| `triggersEnabled` | `true` or `false` (default `false`). Whether committed writes fire triggers. Separate from `scriptsEnabled` because a trigger runs code with no client asking for it; trigger DDL works either way |
| `triggerThreads` | Workers on the trigger executor (>= 1, default `2`). Its own pool, not the background index queue, so a slow trigger cannot stall field-index maintenance |
| `triggerQueueSize` | Bounded trigger queue (>= 1, default `10000`). On overflow the oldest queued event is dropped with a warning and counted in `GET_DATABASE_STATS` |
| `triggerQueueMaxBytes` | Human-readable size > 0 (default `64Mb`). Byte bound on the same queue, with the same overflow rule. A queued `CREATED`/`UPDATED` event holds only ids (the documents are re-read when it runs); a `DELETED` event holds the deleted documents, which is what this bounds |
| `triggerMaxPerCollection` | Valid number ≥ 1 (default `50`). A `SAVE_TRIGGER` creating one more trigger on the collection is refused with `400-32` |
| `triggerMaxDepth` | How deep a chain of trigger-fired writes may go (>= 0, default `3`). A trigger with `allowCascade=false` (the default) already fires nothing above depth 0 |
| `triggerTimeoutMs` | Max wall-clock ms a single trigger run may take (>= 1, default `1000`). Tighter than `scriptTimeoutMs` because nobody is waiting on the result |
| `triggerRunLogEnabled` | `true` or `false` (default `true`). Whether a fired trigger is recorded durably before it runs, so a run queued when the process dies is replayed at startup. Effects and the consumption of the record commit together, so a replay cannot double-apply; off trades that for one less admin write per fired trigger. The record is **node-local** — see [docs/clustering.md](docs/clustering.md) → *Pending trigger runs are node-local* |
| `triggerRunRetentionMs` | Valid number ≥ 1 (default `86400000`). How long a pending trigger-run record is kept before it is garbage-collected as stranded — its collection was dropped, or the node that owned it never came back |
| `beforeHookInstructionBudget` | Valid number ≥ 1 (default `200000`). Instruction budget for the before triggers of **one request** — a `BULK_SAVE` of N documents shares it rather than getting a fresh one per row. A client write is blocked while a hook runs, so it is deliberately an order below `scriptInstructionBudget`. |
| `beforeHookTimeoutMs` | Valid number ≥ 1 (default `200`). Wall clock all the before triggers of one request may take together. Exceeding it **refuses the write** (`408-1`): a hook that could not run must never let a write through. |
| `schedulesEnabled` | `true` or `false` (default `true`). Whether schedules fire; the three schedule operations answer `403-2` while off. Separate from `scriptsEnabled`/`triggersEnabled` because a scheduled run executes code on a clock with its installer's authority and no client asking. Leaving it on costs a ticker thread until somebody installs a schedule |
| `scheduleThreads` | Valid number ≥ 1 (default `2`). Workers running scheduled procedures. Its own pool rather than the trigger executor's, because a scheduled job may hold a worker for its whole timeout |
| `scheduleQueueSize` | Valid number ≥ 1 (default `100`). Bounded queue of due runs; on overflow the oldest is dropped with a warning and counted, since no client is waiting and the schedule fires again at its next occurrence |
| `scheduleTickMs` | Valid number ≥ 1 (default `1000`). How often the scheduler looks for due schedules. Firing is never finer-grained than this, so a schedule whose `intervalMs` is below the tick fires once per tick |
| `scheduleRefreshMs` | Valid number ≥ 1 (default `60000`). How often the whole schedule registry is rebuilt from disk. The DDL path updates it directly; this is the safety net for schedules that arrived through cluster replication or admin anti-entropy |
| `scheduleTimeoutMs` | Valid number ≥ 1 (default `30000`). Default wall clock for one scheduled run; a schedule may override it with its own `timeoutMs` |
| `scheduleMaxPerDatabase` | Valid number ≥ 1 (default `100`). Cap on schedules per database, so a `SAVE_SCHEDULE` loop cannot make the per-tick scan unbounded |
| `scheduleMaxTotalPerUser` | Valid number ≥ 1 (default `500`). Cap on the schedules one user defines across every database, so `MANAGE` on many databases cannot multiply `scheduleMaxPerDatabase`; past it `400-33` |
| `scheduleMinIntervalMs` | Valid number ≥ 1 (default `1000`). Smallest `intervalMs` a `SAVE_SCHEDULE` may ask for (`400-35` below it); a stored or replicated definition below it fires at this interval |
| `faultInjectionEnabled` | `true` or `false` (default `false`). **Testing and debugging only — never in production.** Lets an admin arm the [fault injection](#fault-injection-testing-and-debugging-only) points over the wire, which can pause or halt the node. While off, those operations answer `403-3` and every point costs one field read. A node started with it on logs a `SECURITY WARNING` |

```
# the port the server listens on
port=8989
# 0 = unlimited connections
maxConnections=100
# unauthenticated connections one address may hold (503-14), and unparseable lines before authenticating
maxUnauthenticatedConnectionsPerAddress=16
maxUnauthenticatedInvalidRequests=5
maxFailedAuthAttempts=5
authFailureDelayBaseMs=250
authFailureDelayMaxMs=30000
maxConcurrentPasswordChecks=0
filePath=db
logPath=logs
maxPageSize=2Mb
syncWrites=true
walCheckpointBytes=8Mb
maxEntrySize=1Mb
# schema evaluation steps one write request may spend before it is refused (400-23)
schemaValidationBudget=5000000
# longest pattern / patternProperties key SAVE_SCHEMA accepts (400-31)
schemaMaxPatternLength=4096
# most patterns one schema may hold (400-39)
schemaMaxPatterns=256
# estimated heap of the node's compiled schema-pattern cache
schemaPatternCacheMaxBytes=32Mb
defaultAdminUsername=admin
# required until the first admin exists; never 'administrator'
defaultAdminPassword=<choose-a-strong-password>
maxMemory=512Mb
# ms a transactional write waits for a busy collection lock before aborting (409-5)
transactionLockTimeoutMs=5000
shutdownTimeoutMs=15000
# largest request line once authenticated (64Kb before); longer lines get 400-22 and are closed
maxRequestSize=64Mb
# unread responses one connection may leave queued before it is closed (413-1)
clientOutboundQueueMaxBytes=16Mb
# ms a new connection has to authenticate (408-4), and ms an open transaction may sit idle (409-16)
authTimeoutMs=10000
transactionIdleTimeoutMs=30000
# per-transaction ceilings (400-26) and maximum age however busy (408-5)
transactionMaxOps=100000
transactionMaxBytes=256Mb
transactionMaxAgeMs=600000
# LISTEN registrations per connection and per server (400-25), per user (400-38), request line (400-37),
# request bytes kept server-wide (503-15) and the re-run wall clock
maxListensPerConnection=64
maxListens=10000
maxListensPerUser=256
listenMaxRequestBytes=1Mb
listenMaxRetainedBytes=64Mb
listenRerunTimeoutMs=10000
# IN/NOT_IN operands and within-polygon vertices (400-27), #vector components, AGGREGATE wall clock (408-6)
# and the bytes one AGGREGATE may build (400-36)
maxFilterOperands=10000
maxVectorDimensions=16384
aggregateTimeoutMs=300000
aggregateMaxResultBytes=256Mb
# TLS: when enabled, plaintext clients are rejected at the handshake
tlsEnabled=false
tlsKeystorePath=certs/lwnrdb.p12
tlsKeystorePassword=change_it
# scripts (RUN_SCRIPT) are on by default; the sandbox is server-fixed, never client-supplied
scriptsEnabled=true
scriptInstructionBudget=10000000
scriptTimeoutMs=5000
scriptMaxDepth=200
scriptMaxSourceBytes=256Kb
scriptMaxMemoryBytes=64Mb
# % of max heap the post-GC live set may reach before a script allocating more is stopped
scriptHeapCeilingPercent=75
scriptMaxResultBytes=16Mb
scriptMaxArrayLength=16777216
scriptCursorBatchSize=500
scriptCursorMaxBatchSize=5000
# node-wide script concurrency cap; x scriptMaxMemoryBytes is the worst-case script heap
maxConcurrentScripts=16
scriptQueueWaitMs=250
# per-tenant slices checked before the node-wide wait; 0 disables. They do not wait: a caller at
# its own ceiling is refused at once. Triggers and schedules take them too, as their definer
maxConcurrentScriptsPerUser=4
maxConcurrentScriptsPerDatabase=8
# outbound HTTP from scripts. On by default so the capability is discoverable; the allowlist takes
# exact hosts, *.sub.domain wildcards, or a bare * for every PUBLIC host (an EMPTY list denies
# everything). Private, loopback and link-local addresses need an exact entry. Ships as * and warns
# at startup - narrow it to the hosts your scripts call
scriptFetchEnabled=true
scriptFetchAllowlist=*
scriptFetchTimeoutMs=5000
scriptFetchMaxResponseBytes=1Mb
# max fetches one script run may have in flight at once
scriptFetchMaxConcurrent=8
# queryable run history in each database's reserved `script_runs` collection, created lazily
scriptRunHistoryEnabled=true
scriptRunHistoryKinds=CALL_PROCEDURE,TRIGGER,SCHEDULE
scriptRunHistoryRetentionMs=604800000
scriptRunHistoryIncludeLogs=false
scriptRunHistoryMaxErrorChars=2000
# stored procedures and triggers; triggers are off by default and gated separately
procedureCacheSize=128
procedureCacheMaxBytes=16Mb
maxProceduresPerDatabase=500
# metadata cache bounds - budgeted SEPARATELY from maxMemory (see below)
metadataCacheMaxBytes=72Mb
metadataCacheMaxEntries=4096
triggersEnabled=false
triggerThreads=2
triggerQueueSize=10000
triggerQueueMaxBytes=64Mb
triggerMaxPerCollection=50
triggerMaxDepth=3
triggerTimeoutMs=1000
# durable pending trigger runs, so a run queued when the process dies is replayed at startup
triggerRunLogEnabled=true
triggerRunRetentionMs=86400000
# a failed after-trigger is retried with a doubling backoff and then dead-lettered, where
# LIST_TRIGGER_RUNS finds it and RESOLVE_TRIGGER_RUN replays or discards it
triggerMaxAttempts=3
triggerRetryBackoffMs=1000
triggerRetryMaxBackoffMs=60000
triggerDeadLetterRetentionMs=604800000
```

**Graceful shutdown.** On SIGTERM the server runs an ordered shutdown within
`shutdownTimeoutMs`: it stops accepting new connections, stops the background sweeps,
rolls back open transactions (releasing their collection locks, but leaving PREPARED
2PC slices for recovery), drains the trigger queue and then the background index queue,
stops the listen workers, and finally leaves the cluster. Work still outstanding when
the budget expires is abandoned with a warning naming what was dropped — an abandoned
index event means the affected collection's field indexes may be stale, so run `REINDEX`
on it. Set `shutdownTimeoutMs` below whatever SIGKILL grace period supervises the
process (Docker's default is 10s, so lower it or raise `--stop-timeout`).

**Two cache budgets, not one.** `maxMemory` bounds the *user* document/index cache
only. The admin metadata caches — stored procedure sources, per-collection JSON
Schemas, trigger lists and schedule definitions — are bounded separately by
`metadataCacheMaxBytes` (the byte-budgeted ones) and `metadataCacheMaxEntries` (the
count-budgeted ones), and sit on top of it. All of them
are LRU-evicted and backed by disk, so lowering them only costs a re-read. Size
`-Xmx` against the sum: the server logs a warning at startup when the budgets total
more than the heap. `GET_DATABASE_STATS` reports the live footprint under
`memory.adminMetadataCache`.

### TLS / secure connections

When `tlsEnabled=true`, the server listens with a JSSE `SSLServerSocket`, so the
same line-delimited JSON wire protocol runs over an encrypted TLS channel: every
request is decrypted and every response is encrypted at the transport layer — the
message format is unchanged. A client that connects without TLS fails the TLS
handshake and its connection is dropped; it never reaches an operation.

The server loads its private key and certificate from a **PKCS12 keystore** at
`tlsKeystorePath`, unlocked with `tlsKeystorePassword`. For production, point
`tlsKeystorePath` at a keystore containing a CA-issued certificate.

If `tlsEnabled=true` but no keystore exists at `tlsKeystorePath`, the server
**generates a self-signed certificate** in-process (using only the JDK — no
external libraries or `keytool` subprocess), writes it to that path so it stays
stable across restarts, and logs a prominent `SECURITY WARNING` at startup. The
self-signed certificate is suitable for development only; clients will not trust
it. Replace it with a proper keystore before running in production.

When `tlsEnabled=false` (the default) the server listens in plaintext exactly as
before, and logs a `SECURITY WARNING` at every startup: passwords, session data and
documents then cross the network unencrypted, so keep such a server on localhost.

With `tlsEnabled=true`, a `tlsKeystorePassword` still at the shipped `change_it` also
logs a `SECURITY WARNING` at every startup: anyone who can read the keystore file can
extract the private key with it. Set your own password in `lwnrdb.cfg` (and re-create or
re-encrypt the keystore under it).

### File permissions

On POSIX filesystems, the folders the server creates — the data root (`filePath`), every database,
collection, `admin/pages`, `.procedures`/`.schedules` and `cluster/` folder under it, the log
folder (`logPath`) and the generated keystore's folder — are created `0700`, and a generated
keystore `0600`, with the mode set in the creating call so there is no moment they are readable
by others. A `0700` data root already shields everything under it, including the password hashes
in `admin/` and the cluster state in `cluster/`. Existing paths are **never** `chmod`-ed: instead a
`SECURITY WARNING` names each of the data root, the log folder, the keystore (with client or cluster TLS on) and
`lwnrdb.cfg` (it holds `defaultAdminPassword`, `tlsKeystorePassword` and `clusterSecret`) that group
or other users can access, with the `chmod 700`/`chmod 600` that fixes it. A Docker volume created
by the runtime is often `0755`, so that warning is expected there until you tighten it. A permission
failure never stops startup; on non-POSIX filesystems none of this applies.

Two file-handling rules follow from the same concern: log cleanup only ever deletes files named
`yyyy-MM-dd.log` that fell out of `maxLogFiles` (any other file in `logPath` is left alone), and
`DROP_DATABASE` / `DROP_COLLECTION` never follow a symbolic link — a linked folder inside a database
is unlinked, and its target's contents are left untouched.

### Clustering (multi-node)

> Full design, implementation details and the operations runbook live in
> [docs/clustering.md](docs/clustering.md). With `clusterEnabled=false` (the default)
> the node behaves exactly as a standalone server, with no behaviour change on any path.

LWNRDB can run as a cluster of fully-replicated nodes with a **distributed cache**:
every collection is consistent-hashed to an **owner node** (there is no single
master — ownership is spread across all nodes), and a client may connect to any
node. Nodes discover each other from a **seed list** and then **gossip** full
membership and heartbeats over a dedicated `clusterPort`, detecting failures by
missed heartbeats.

To form a cluster, enable it on each node and point new nodes at one or more
seeds:

```
clusterEnabled=true
clusterPort=9990
clusterAdvertisedAddress=10.0.0.11      # this node's reachable address
clusterSeeds=10.0.0.10:9990             # an existing node (empty on the first node)
clusterSecret=change_this_to_a_shared_secret_of_32_plus_characters
```

The node-to-node channel uses the same line-delimited JSON transport as clients,
authenticated with `clusterSecret` (at least 32 characters, sent in a mandatory
leading `HELLO` frame and on every frame after it) and optionally encrypted with
`clusterTlsEnabled`. Cluster TLS is **mutual**: it reuses the PKCS12 keystore
machinery, every node presents the keystore's certificate, and the cluster
listener requires a client certificate the same keystore trusts — so all nodes
must share one keystore (or one CA), and a process with any other certificate
cannot connect. Every peer-supplied name, document id and version is validated
on arrival, and a forwarded request is re-validated and re-authorized for its
acting user on the node that runs it (see [docs/clustering.md](docs/clustering.md)
→ *Wire protocol*). All nodes must run the same build: a node from before the
`HELLO` handshake cannot join a cluster of newer nodes.

### Memory management

`maxMemory` bounds the **user cache**: a background sweep (every 5s) sums the **modelled heap size** of the cached user collections and indexes and, while that total exceeds the budget, evicts the least-frequently-used of them. Each document and index entry is sized from the JVM's object layout — object headers (compact or not), reference width (4 bytes, or 8 under ZGC or a heap of 32 GB and more), alignment, `String` backing arrays, map entries and hash tables — so the total tracks the heap the cache really holds rather than the documents' on-disk size, which is several times smaller (a small document costs about 3 KB of heap against under 200 bytes on disk). It is still the cache's own accounting, not measured JVM heap — nothing reads real heap to decide an eviction (`GET_DATABASE_STATS` reads it only to report). Values are human-readable (e.g. `512Mb`, `2Gb`). Two special values are accepted:
- `0` — unlimited; caching is on but eviction never triggers (suitable when `-Xmx` is already the only ceiling you want).
- `-1` — caching disabled; user collections and indexes are always read from disk. Admin collections are always cached regardless.

Eviction order is LFU. Access counts are recorded asynchronously and persisted in the `admin/collection_usage` collection; records older than 24h are pruned hourly. Within the cache, PK indexes are preferred over field indexes, which are preferred over full document maps.

**Sizing `-Xmx` against the cap.** `maxMemory` bounds the cache's real heap, so the rest of `-Xmx` is what the collector, the page a scan is reading, in-flight requests and scripts get. Size `-Xmx` to roughly twice `maxMemory`, plus the metadata cache and script budgets; startup logs a warning when `-Xmx > maxMemory × 2`, suggesting `maxMemory` around half of `-Xmx`. `maxMemory` cannot reclaim metaspace, JIT code or committed-but-unused heap, so the OS-visible process size stays above it.

Earlier versions charged each document its on-disk size and each index entry a flat 64–96 bytes, which undercounted the real heap 3–8×. The same data now takes a larger share of `maxMemory`, so a deployment tuned to the old numbers evicts sooner and may want a larger cap.

**Streaming reads.** Queries no longer load an entire collection into memory before filtering. When a `FILTER` step matches against an index, only the matched entries are fetched via positioned reads (the whole collection is never loaded). A conjunction that starts the pipeline first combines its legs' id sets from the indexes (`AND` intersects the indexed legs and tests the others on what survives; `OR`/`XOR` take the union when every leg is indexed) and then fetches only the surviving documents once, so `price >= 20 AND price < 50` reads the documents in that range rather than each bound's half of the collection. Two or more numeric bounds on the same indexed field inside an `AND` are one walk of the sorted index. `NOR`/`NAND`, and a conjunction holding a vector `nearest`, keep reading every leg as before. When there is no usable index, the collection is scanned page-by-page: one page is resident at a time, each page's modelled heap (a sample of its documents scaled to the page) drives a between-pages headroom check that evicts other cached resources when the budget is tight, and consumed pages are released for GC.

`SORT`, `GROUP_BY`, `JOIN`, and `DISTINCT` also use a single-field index when one exists on the step's field **and** the step is the pipeline source (no earlier step has already produced a stream). The field index maps each value to its matching ids, so the step works from that grouping instead of scanning the whole collection: `DISTINCT` reads no documents at all (the index keys are the distinct values), `GROUP_BY`/`SORT` fetch only the grouped/ordered documents via positioned reads, and `JOIN` fetches only the remote documents whose value matches a local value. Because indexes model scalar/custom/null values only, documents whose indexed field holds a JSON object or array are outside index scope and do not appear in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results; run the step on a non-indexed field if you need those included. When no index applies, these steps still materialize their working set in memory as before.

> **Note — an index buys `GROUP_BY` little (often nothing).** Unlike `DISTINCT`/`FILTER`/index-only `COUNT`, an index-backed `GROUP_BY` cannot avoid reading documents: its output places every grouped document into a `group` array, so every matched document is still fetched. The index only supplies the value→ids buckets, which replaces a cheap in-memory `groupingBy` — a negligible saving. On a field present in (nearly) every document this yields **effectively no speed-up** over a plain scan, and is in fact slightly *worse*: the index path fetches via random positioned reads and materializes the whole working set (the id set, a `docById` map and the grouped list) in memory, whereas the scan path streams page-by-page with the between-pages headroom check. The fast-path helps **only when the grouped field is sparse** (few documents carry it) — there, like `FILTER`, it reads just the matching documents instead of every page. The index fast-path is kept for that sparse case, but `GROUP_BY` should not be expected to benefit from an index the way the other steps do.
Object- and array-valued fields are covered only by the **element-match hash indexes** used at the `FILTER` step (`EQUALS`/`NOT_EQUALS`/`IN`/`NOT_IN`), stored in separate `…-Object.idx` / `…-Array.idx` files; those hashes cannot be reconstructed or ordered, so `GROUP_BY`/`SORT`/`DISTINCT` still skip object/array values.

A `COUNT` (with the collection as the pipeline source) is answered from the indexes alone — without reading any documents — whenever every step before it either filters via an index or leaves the document count unchanged. `FILTER` steps are resolved to id-sets through their indexes; sequential filters compose as `AND`, so the count is the size of the **intersection** of their id-sets. A single indexed field operator resolves through its field index; a conjunction resolves when every leaf is index-resolvable, combining the per-leaf id-sets with set algebra (`AND` = intersection, `OR` = union, `XOR` = exactly-one, and `NOR`/`NAND` = the complement against the full id universe taken from the PK index). Count-preserving steps between the filters and the `COUNT` — `MAP`, `JOIN`, `SORT` — are skipped entirely (they emit one row per input row, and `COUNT` discards their transformed output; `JOIN` permissions are still checked before execution). A `FILTER` is only index-resolvable while it still sees the stored documents, so once a `MAP`/`JOIN` has modified them no later `FILTER` can use its index. A single numeric range, or an `AND` of numeric bounds on one indexed field, is counted straight from the index slice without building an id set, unless writes to the collection are still being indexed. If a step changes the count in a data-dependent way (`GROUP_BY`, `DISTINCT`, `LIMIT`, `SKIP`), or any leaf lacks a usable index, the count falls back to counting the filtered stream as before.

### Concurrency & locking

Locking is two-tier and applies to **both reads and writes** (earlier versions locked only writes):

- **Collection-level read/write locks.** Each collection (and each field index) has a read/write lock. Reads acquire a *shared* read lock; writes (`SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`) acquire an *exclusive* write lock. While a writer holds a collection, nobody else may read or write it; multiple readers run concurrently. An `AGGREGATE` with `JOIN` steps read-locks the primary collection and every joined collection, acquiring them in a deterministic order so overlapping queries cannot deadlock. Cache eviction only evicts a resource it can exclusively (write) lock, so it never races an in-flight read or write.
- **File-level read/write locks.** Below the collection tier, each physical `.dat`/`.idx` file has its own read/write lock, so a file's bytes are never read while they are being rewritten.

**Dirty reads.** Read operations (`FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `LIST_USERS`) accept an optional `"dirtyRead": true` (default `false` = fully locked). A dirty read **skips the collection-level read lock**, so it can proceed even while a long write holds the collection. It still goes through the file-level read locks, so every page/index file it reads is individually valid (never half-written). A dirty read may observe a mix of pre- and post-write pages across a collection, may see a document that a concurrent write is relocating twice in one full scan (so a dirty `COUNT` can over-count), and may fail with a retryable error when a concurrent write moves a document out from under the offset it was about to read — it never answers with a *different* document than the one asked for. That is the trade-off for not waiting.

**Transactions.** A connection can open a transaction (`START_TRANSACTION`) to make several writes atomic (see [Transactions](#transactions)). Writes inside a transaction are buffered in the internal `admin/transactions` collection rather than applied, and become visible to the real collections only on `COMMIT_TRANSACTION`; `ROLLBACK_TRANSACTION` discards them. Locking is **lazy**: the first buffered write to a collection acquires that collection's exclusive write lock (waiting up to `transactionLockTimeoutMs`, then aborting the transaction with `409-5` so two concurrent transactions can never deadlock) and holds it — on the connection's own virtual thread — until commit/rollback. 
While held, other connections cannot read or write those collections, which is what keeps the committed batch atomic. The transaction's own reads apply its buffered mutations (**read-your-writes**); there is no snapshot isolation against other connections beyond that write-exclusivity. Committing replays the buffered operations through the normal write path, so field-index maintenance, page metadata and `LISTEN` notifications behave exactly as for ordinary writes. If the connection drops with a transaction open it is auto-rolled-back, and so is one left idle for `transactionIdleTimeoutMs` (the next request then answers `409-16` once). After a crash, a transaction that had not reached its commit point is discarded at startup, and one that had is finished.

### Consistency guarantees and known limitations

What a single node promises, and what it deliberately does not. The multi-node model is described in [docs/clustering.md](docs/clustering.md) → *What the cluster actually guarantees*.

**Guaranteed**

- **An acknowledged write survives a process crash.** Writes go to the files before the response is sent, so a `kill -9` or a JVM crash loses nothing that was acknowledged.
- **An acknowledged write survives a power loss** while `syncWrites` is on (the default). Every write is first appended to its collection's write-ahead log as the exact bytes it puts at each offset of each page and of `pk.idx`, and that record is synced to the device before the pages and `pk.idx` are touched; a created, renamed or deleted file has its folder synced too. Startup replays the log over whatever the files hold, so a power loss leaves every acknowledged write and no unacknowledged one. On a platform that cannot sync a directory the server refuses to start with `syncWrites=true`. See [Durability](#durability) for what it costs.
- **A torn write heals at startup.** A half-written trailing line in a page, `pk.idx`, field-index log or tombstone file is skipped and the file rewritten without it. Page-occupancy metadata is rebuilt from the live bytes `pk.idx` names. An update or delete never moves another record: it appends the new copy, appends its `pk.idx` row, then blanks the old copy into a hole, and the whole write is one record in the collection's write-ahead log (`.wal`), logged before any of it. Startup replays every logged record and cuts the bytes past a logged end, so an interrupted write is either whole or absent. An interrupted page vacuum, which rewrites a page outside the log, keeps an intent in the collection's `.intents` log and re-derives the rows of the page it rewrote. That step needs a readable `pk.idx`; when it is not, startup logs the intent and retries at the next start, and [`REINDEX`](#reindex) with `"primaryKey": true` settles it and rebuilds the `pk.idx` from the pages.
- **Index and scan answer identically.** Field-index maintenance is asynchronous, but an index-backed read reconciles the documents not yet indexed, and an index that does not cover every document declines so the query scans. The one exception is vector `nearest` (below).
- **Transactions are atomic and write-exclusive**, with read-your-writes inside the transaction and no snapshot isolation outside it (see [Concurrency & locking](#concurrency--locking)). A crash never leaves half a transaction applied.
- **After-triggers are not lost to a crash** while `triggerRunLogEnabled` is on (the default): a run is recorded durably before it runs and replayed at startup, and its effects commit together with the consumption of that record, so a replay cannot apply them twice. A run that keeps failing is retried and then dead-lettered, never dropped silently.

**Not guaranteed, by design**

- **Device durability with `syncWrites=false`.** A write is then acknowledged once the operating system has it, so a power loss or an operating-system crash can lose recently acknowledged writes; the server says so in a startup warning. Turn it off only on storage with power-loss protection, or in a [cluster](#clustering-multi-node) where a second node holds the write.
- **Field indexes after a crash with index work pending.** Work for writes still in the collection's write-ahead log is re-queued at startup and needs nothing from you. Work the log no longer covers (a checkpoint dropped the records while it was pending, or it came from a startup heal or a `REINDEX` with `"primaryKey": true`) can miss entries, so an index-backed query can omit those documents until a full [`REINDEX`](#reindex). Startup logs a warning naming each collection in that state, and the `-indexes.unclean` marker behind it stays until a `REINDEX` rebuilds every registered index of the collection.
- **Exact vector search by default.** `nearest` is an approximate (ANN) search unless `"exact":true` is passed (see [custom operators](#custom-operators-type-specific)).
- **Catching up on schedules.** A scheduled procedure fires at most once per due instant, and runs missed while the node was down are skipped (see [`SAVE_SCHEDULE`](#save_schedule)).
- **Isolation for dirty reads.** A read with `"dirtyRead":true` can observe a write half-way through (see [Concurrency & locking](#concurrency--locking)).

### Durability

With `syncWrites=true` a write is acknowledged once its record in the collection's write-ahead log
(`{collection}.wal`) is on the device: one sequential append and one force, whatever the write is. An insert, an
update, a delete, a relocation and a whole bulk write each log one record holding the exact bytes they put on each
page and in `pk.idx`, then write those files without forcing them. A checkpoint forces every page and `pk.idx` the log
touched since the last one, in parallel, then rewrites the log as a single checkpoint record; it runs once the log
passes `walCheckpointBytes`, before a page vacuum or a `pk.idx` rewrite, before a `REINDEX` with `"primaryKey": true`,
and at shutdown. After a power loss startup replays the log over whatever the files hold and cuts each file back to
its last logged end, so an acknowledged write is redone and an unacknowledged one leaves nothing. With
`syncWrites=false` there is no cut, since the bytes past a logged end may then be an acknowledged write whose record
did not reach the disk. A write whose
record is logged but whose files cannot be written is taken back and marked aborted in the log; when even that fails,
it is applied before the next write to the collection, or by the next startup. A write does not write the
collection's index-dirty marker either: its log record is what tells startup that its field-index work may be
unfinished, so startup re-queues that work for every id the replayed log wrote. Only a checkpoint that drops records
while index work is still pending writes the marker, and shutdown leaves such a collection's log in place for the
next startup to replay instead. Clearing an index marker is not forced, because a power loss that undoes it leaves a
journal behind that startup already settles. A
force costs what the disk makes it cost, and the per-collection write lock is held across them, so it bounds the
write rate of one collection. Measured for one force: about 0.25 ms on a Docker named volume (Linux, Docker
Desktop VM) and about 4 ms on an Apple SSD under macOS, a cost consistent with a full device flush (not yet confirmed
there; the Docker image on Linux is the production target). Different collections write in parallel. Logs and the generated TLS keystore are not forced.

## Q&A

- I want X feature. Can you add it for me?
  - If it feasible and it makes sense, sure! But it might take some time. Please submit an issue
- Can I use it in production?
  - I wouldn't recommend doing that at least for now, as it is very experimental
- I discovered a bug. What should I do?
  - Please submit an issue with the steps to reproduce it and the expected result.

## Code quality

Formatting and static analysis run as part of `mvn verify`, so a violation
fails the build (and therefore blocks a merge). All five are wired into
the `verify` phase. The structural tests scan the source tree itself: no file may
gain a comment (`config/comment-baseline.txt` records the existing ones and only
goes down), and a handful of architectural rules — package boundaries, the single
`maxEntrySize` check, where triggers may be staged — are checked on every build:

| Tool | Goal | Config |
|---|---|---|
| **Spotless** (Eclipse JDT formatter, 4-space) | `spotless:check` | [`config/eclipse-format.xml`](config/eclipse-format.xml) |
| **Checkstyle** | `checkstyle:check` | [`config/checkstyle.xml`](config/checkstyle.xml) |
| **PMD** | `pmd:check` | [`config/pmd-ruleset.xml`](config/pmd-ruleset.xml) |
| **SpotBugs** | `spotbugs:check` | [`config/spotbugs-exclude.xml`](config/spotbugs-exclude.xml) |
| **Structural tests** (JUnit, `org.techhouse.unit.structure`) | `test` | [`config/comment-baseline.txt`](config/comment-baseline.txt) |

To auto-format your changes before committing:

```bash
mvn spotless:apply
```

> **Build JDK: JDK 27 or newer is required** (matching the project's compiler
> target and CI); `mvn verify` fails on anything older. The floor is not
> arbitrary: the SimpleJS regex engine resolves `\p{...}` property escapes
> against the JDK's own Unicode data, so an older JDK ships an older UCD and the
> same script answers differently — including between two nodes of one cluster.
> The Eclipse JDT formatter is used instead of Google/Palantir formatters
> specifically because the latter rely on `javac` internals that are
> incompatible with recent JDKs.

The linter rulesets are deliberately curated rather than using defaults: they
target real defects and conventions the formatter does not cover, while
accommodating the project's intentional choices (e.g. the `_id` wire field,
`snake_case` test method names, `null`-as-absent sentinels for index/config
lookups). Exclusions are documented inline in each config file.

## Contributing

Pull requests are welcome! For major changes, please open an issue first
to discuss what you would like to change.

Please make sure to update tests as appropriate and add new ones if a new feature is developed or a big bug solved.
