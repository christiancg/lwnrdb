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

## Pending tasks

- [ ] Javascript engine to support additional features 
  - [x] Stored procedures (the [`SAVE_PROCEDURE`](#save_procedure) / [`CALL_PROCEDURE`](#call_procedure) operations)
  - [x] Triggers (the [`SAVE_TRIGGER`](#save_trigger) operation)
  - [x] Scheduled procedures (the [`SAVE_SCHEDULE`](#save_schedule) operation)
  - [x] Run script (the [`RUN_SCRIPT`](#run_script) operation)
  - [x] Script node selection under clustering: [`RUN_SCRIPT`](#run_script) and [`CALL_PROCEDURE`](#call_procedure) are forwarded to a live node chosen by current script load (`scriptRoutingEnabled`, on by default), skipping any node not yet caught up on admin metadata — see [docs/clustering.md](docs/clustering.md) → *Scripts*
    - [ ] Locality-aware placement: selection is by load only, so the chosen node is usually not the owner of the collections the script touches and every operation the script issues still costs a round trip
- [x] Add ability to restrict the save of a document taking into consideration a specific format. Reject write if not compliant (per-collection JSON Schema — see [Schema validation](#schema-validation)) 
- [x] Replication between nodes (no master-slave arch; all nodes are equal; no sharding) — see [docs/clustering.md](docs/clustering.md)
- [x] Move pages admin collections to a separate folder called "pages" to make things more organized
- [x] Transactions
- [x] Vector type support
  - [x] Semantic search
- [x] Geo type support
  - [x] Distance operator
  - [x] Within operator
- [x] Listenable queries (you create the query and then the DB sends events when there are changes)
- [x] Explain / Analyze with index and query suggestions
- [x] Integration tests for all possible API commands, including aggregations
- [x] Standardized error messages with error code, following HTTP patterns: 4xx → user error, 5xx → server error, ending with a specific number per error. Ie 401-1 "need to authenticate"
- [x] Admin operation to rebuild indexes
- [x] Use ZGC as garbage collector in Docker image. Also recommend using that one when running locally in this file
- [x] Sort operation doesn't seem to be faster with indexes
- [x] Group by operation isn't meaningfully faster with indexes — it must read every grouped document either way, so an index only helps when the grouped field is sparse (see Memory management → Streaming reads). The fast-path is retained for that sparse case
- [x] Join operation doesn't seem to be faster with indexes
- [x] Remove pending consistency issues with cache, fs and index usage
- [x] Remove the most impactful consistency issues with cache, fs and index usage
- [x] Issue when concurrently writing to a field index and trying to read from it (might get wrong values because index is not updated)
- [x] Index usage for object and array fields (element-match via hashed object/array indexes; `FILTER` `EQUALS`/`NOT_EQUALS`/`IN`/`NOT_IN` only)
- [x] Separated caches for admin entries and user entries (the `Cache` facade composes an `AdminCache` for admin metadata and a `UserCache` for memory-managed user documents/indexes)
- [x] Date type support
- [x] Index usage in:
  - [x] group by
  - [x] join
  - [x] sort
  - [x] distinct
  - [x] count
- [x] Better file locks
- [x] 95% test coverage
- [x] Request validation
- [x] Iterative read depending on available memory and document count
- [x] Collection and index eviction from cache depending on memory usage and query history (using LFU algorithm — see `cache/MemoryManagement` and the `maxMemory` configuration)
- [x] Numerical values that are integers shouldn't be printed with ".0"
- [x] Users and permissions
- [x] Secure connections with TLS or something similar
- [x] Remove lombok
- [x] Check that in join aggregations, the user should have permissions to the collection that is being joined
- [x] Validation of configurations
- [x] Review and address TODOs
- [x] Remove all warnings from code
- [x] Fix tests marked as @Disabled
- [x] Implement linting and formatting

## Wire Protocol / Message Reference

All messages are line-delimited JSON sent over a TCP connection. Every request must include a `type` field. Responses always contain `type`, `status` (`OK`, `ERROR`, `NOT_FOUND`, `UNAUTHENTICATED`, `FORBIDDEN`), and `message`. Error responses also include an `errorCode` field (absent on success) with the format `NNN-N` — a three-digit HTTP-style range prefix and a sequential number (e.g. `401-1`). The 4xx range covers client errors; 5xx covers server errors; 503 means the server is temporarily unavailable.

### Naming rules

- **Database / collection names**: 3–64 characters, alphanumeric + `_` and `-`. The name `admin` is reserved.
- **IDs (`_id`)**: 1–64 characters, alphanumeric + `_` and `-`.

### Operations

#### `LIST_DATABASES`
```json
{"type":"LIST_DATABASES"}
```

#### `CREATE_DATABASE`
```json
{"type":"CREATE_DATABASE","databaseName":"my_db"}
```

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
| `FILTER` | `operator` | Field or conjunction operator |
| `MAP` | `operators` (non-empty) | Each operator needs `fieldName` |
| `GROUP_BY` | `fieldName` | |
| `JOIN` | `joinCollection`, `localField`, `remoteField`, `asField` | `joinCollection` must satisfy naming rules; the user must also have `READ` on `joinCollection` |
| `COUNT` | — | Returns `{"count": N}` |
| `DISTINCT` | — | `fieldName` is optional; omitting it deduplicates whole documents |
| `LIMIT` | `limit` (> 0) | |
| `SKIP` | `skip` (>= 0) | |
| `SORT` | `fieldName`, `ascending` | |

`GROUP_BY`, `JOIN`, `SORT`, and `DISTINCT` use a single-field index when one exists on the step's field and the step is the first step in the pipeline; otherwise they fall back to a full scan. These steps use only the scalar/custom/null indexes, so documents whose indexed field holds a JSON object or array are not represented in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results (see [Memory management → Streaming reads](#memory-management)). 
Object- and array-valued fields are instead indexed for **element-match** (whole-value equality): a `FILTER` with `EQUALS`, `NOT_EQUALS`, `IN`, or `NOT_IN` hashes the object/array and resolves it through a dedicated per-kind hash index (`…-Object.idx` / `…-Array.idx`).

A collection may also carry a single JSON Schema stored alongside its data files as `{collection}-schema.json` (see [Schema validation](#schema-validation)). Ordering/containment operators (`GREATER_THAN*`, `SMALLER_THAN*`, `CONTAINS`) and the reconstructing steps above cannot use these hash indexes because a hash cannot be ordered or turned back into a value.

**Field operator types:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`

**Conjunction operator types:** `AND`, `OR`, `NOR`, `XOR`, `NAND`

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
Returns `400-8` if the supplied schema is not a valid 2020-12 schema.

#### `DELETE_SCHEMA`
Removes the collection's schema, so writes are no longer validated. Idempotent — returns `OK` whether or not a schema existed. Requires admin privileges or database ownership.
```json
{"type":"DELETE_SCHEMA","databaseName":"my_db","collectionName":"my_coll"}
```

#### `LISTEN`

Subscribe to live query results. The server runs the aggregation once and returns the initial results with a UUID listen ID and a SHA-256 hash of the result set. Whenever documents in the collection change, the server re-runs the query in the background; if the new hash differs from the last sent hash, a push message is sent to the same connection with the updated results.

Accepts the same `aggregationSteps` as `AGGREGATE` (all step types, same validation rules). Requires `READ` on the base collection; `JOIN` steps additionally require `READ` on each joined collection.

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

If the connection closes with a transaction still open, it is automatically rolled back.

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
  "logsTruncated": false
}
```

- `script` (required) is the program source, `args` (optional) is an arbitrary object the script reads through `import args from "args"`, and `db.name` is the scoped database name — so one script can run against any database without hardcoding it.
- `result` is the script's value: a top-level `return`, else `export default`, else an object of the named exports, else JSON `null`. A promise returned at top level is awaited (a rejection becomes the error); one that never settles yields `null`.
- `logs` holds the script's `console` output — the newest `scriptMaxLogLines` lines, each clipped to `scriptMaxLogLineChars` — and `logsTruncated` reports whether anything was dropped. Output is returned on **every** outcome, including a failure, so a failed run is still debuggable.
- **Permissions**: admins may run scripts on any database and database owners on the databases they own; any other user needs a **per-database** script grant — `scriptPermissions: {"mydb": "RUN"}` on their user record (the older boolean form is still accepted and reads as `RUN`). Every operation the script itself issues is authorized again on its own request, so the grant never widens what the caller can read or write, and the collection schema still applies to a script's writes. A script cannot leave its database (`admin` included) — an attempt throws a catchable error inside the script.
- The exposed surface is read+write only (`findById`, `aggregate`, `save`, `bulkSave`, `delete`, `cursor`, `listCollections`, `listDatabases`, `transaction`); DDL, user management and outbound network access are not reachable from a script.
- **`db.cursor(database, collection, pipeline, options)`** is the memory-safe way to read more than fits in one result: it returns an iterator that runs the pipeline one page at a time (`SKIP`/`LIMIT` appended per batch), so only one batch is ever in memory and only that batch counts against `scriptMaxMemoryBytes`. `options.batchSize` defaults to `scriptCursorBatchSize` and is clamped to `scriptCursorMaxBatchSize`; a non-positive value is a `RangeError`. It works with `for-of`, spread and the iterator helpers (`.map`/`.take`/…).

  ```js
  import db from "db";
  let total = 0;
  for (const order of db.cursor(db.name, "orders",
          [{ type: "SORT", fieldName: "_id", ascending: true }], { batchSize: 500 })) {
      total += order.amount;
  }
  return total;
  ```

  It is a **paged read, not a snapshot**: each batch is an ordinary `AGGREGATE` against the live collection (authorized, schema-checked and cluster-routed like any other), so a concurrent insert or delete between two batches can make a document be seen twice or not at all. Paging is only meaningful with a `SORT` step — without one the pipeline's order is unspecified — and `db.cursor` does not inject one, because that would change the results of a pipeline ending in `GROUP_BY`/`COUNT`. Such a pipeline still works, but it pages the *step output*, which is rarely what is meant.
- **Every failed `db` operation throws a catchable `Error` inside the script** — a permission denial, a schema violation, an oversized entry, a cluster rejection or an internal error alike; a failure is never silently swallowed. The two exceptions are ordinary absence rather than failure: a missing document reads as `null` from `findById`, an empty pipeline as `[]` from `aggregate`, and deleting a document that is not there is a no-op.
- **Errors**: `403-2` when scripting is disabled, `403-1` when the caller may not run scripts, `404-4` for an unknown database, `400-10` when the source exceeds `scriptMaxSourceBytes`, `400-9` when the script throws or fails to parse (the `message` is `"<ErrorName>: <message>"`), `400-11` when it exceeds the instruction or depth budget, `400-12` when it exceeds `scriptMaxMemoryBytes`, `400-15` when its result exceeds `scriptMaxResultBytes`, `408-1` when it exceeds `scriptTimeoutMs`, and `409-6` if sent while a transaction is open on the connection.
- Under clustering the script runs on the node that received it; each operation it issues is routed to its collection's owner, and `db.transaction` spans owners through the same 2PC the wire protocol uses.

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
- **Errors**: `403-2` scripting disabled, `403-1` not permitted, `404-4` unknown database, `400-1` an invalid name (3–64 alphanumerics plus `_` and `-`, the same rule as a collection) or a blank script, `400-10` source over `scriptMaxSourceBytes`, `400-13` source that does not parse (the message carries the line and column), `409-8` version conflict.

#### `DELETE_PROCEDURE`
```json
{"type":"DELETE_PROCEDURE","databaseName":"mydb","name":"recalcTotals"}
```
Idempotent — succeeds whether or not the procedure existed. Refused with `400-14` while a trigger still references it (the message names the trigger).

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
`includeSource: true` adds the `source` field. Requires `READ` on the database.

#### `CALL_PROCEDURE`
Runs a stored procedure. The permission is the same as [`RUN_SCRIPT`](#run_script)'s (`scriptPermissions` of at least `RUN`), the sandbox is the same, and the response shape matches — `result`, `logs`, `logsTruncated`, with the same error codes.

```json
{"type":"CALL_PROCEDURE","databaseName":"mydb","procedureName":"recalcTotals","args":{"id":"o1"}}
```
```json
{"type":"CALL_PROCEDURE","status":"OK","message":"Procedure executed successfully","result":42,"logs":[],"logsTruncated":false}
```

- The procedure runs with the **caller's** authority, so calling one never grants more than the caller already had.
- The parsed program is cached per node keyed by `(database, name, version)`, so a repeated call does not re-parse. `procedureCacheSize` bounds it.
- **Errors**: as `RUN_SCRIPT`, plus `404-8` when the procedure is absent or disabled.

#### `SAVE_TRIGGER`
Runs a stored procedure after a committed write to a collection. Requires admin privileges, ownership, or `MANAGE`. Off by default: set `triggersEnabled=true` for triggers to fire (the DDL works either way).

```json
{
  "type": "SAVE_TRIGGER",
  "databaseName": "mydb",
  "collectionName": "orders",
  "name": "auditWrites",
  "events": ["CREATED", "UPDATED", "DELETED"],
  "procedureName": "recalcTotals",
  "mode": "document",
  "allowCascade": false,
  "enabled": true,
  "ifVersion": 1
}
```
```json
{"type":"SAVE_TRIGGER","status":"OK","message":"Trigger saved successfully","version":2,"definer":"alice"}
```

- **Fires after the write commits**, asynchronously, on its own worker pool. It therefore cannot reject or modify the write — use a [collection schema](#schema-validation) for that — and a trigger failure never reaches the writer; it is logged and counted in [`GET_DATABASE_STATS`](#get_database_stats-admin-only).
- **Runs with the installer's authority** (`definer`), not the writer's, so it behaves identically no matter who wrote — which is what lets an audit trigger record a write by a user who has no access to the audit collection. Re-saving re-stamps the definer to the saving user. If the definer is deleted the trigger stops firing (it never falls back to the writer or to an admin).
- **A write inside a transaction fires when the transaction commits**, never before, so a rolled-back write fires nothing. The event is the one the write actually performed: a new document fires `CREATED`, an overwrite `UPDATED`, and a delete fires `DELETED` carrying the document as it stood when the delete was buffered.
- `mode` is `document` (one run per document, the default) or `batch` (one run for a whole `BULK_SAVE`).
- `allowCascade` defaults to `false`, so writes a trigger itself performs fire nothing. With it on, a chain terminates at `triggerMaxDepth`.
- The procedure receives `{event, database, collection, id, document, trigger, actingUser, definer, firedAt, depth}` as its `args` — `actingUser` is who wrote, `definer` is whose authority the run has. In `batch` mode `documents` replaces `id`/`document`.
- Triggers are stored **with their collection**, in `{filePath}/{database}/{collection}/{collection}-triggers.json`, so dropping the collection removes them.
- **Errors**: `403-1` not permitted, `404-4` unknown collection, `404-8` unknown or disabled procedure, `400-14` no events / an unknown event / an unknown mode, `409-8` version conflict.

#### `DELETE_TRIGGER`
```json
{"type":"DELETE_TRIGGER","databaseName":"mydb","collectionName":"orders","name":"auditWrites"}
```
Idempotent — succeeds whether or not the trigger existed.

#### `LIST_TRIGGERS`
```json
{"type":"LIST_TRIGGERS","databaseName":"mydb","collectionName":"orders"}
```
Omit `collectionName` to list every trigger in the database. Each entry carries its `collectionName` and `definer`. Requires `READ` on the database.

#### `SAVE_SCHEDULE`
Runs a stored procedure **on a clock**. Requires admin privileges, ownership, or `MANAGE` — the same bar as installing a trigger, and for the same reason: a scheduled run executes with its installer's authority. On by default; set `schedulesEnabled=false` to refuse the three schedule operations (`403-2`) and stop anything already installed from firing. Installing one still requires `scriptsEnabled`, since a schedule can only name a stored procedure. Idempotent upsert: saving an existing name replaces it and bumps its `version`.

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
- **Runs with the installer's authority** (`definer`), like a trigger and for the same reason: a scheduled run has no caller, so running it as the installer makes it behave identically regardless of who happens to be connected. Re-saving re-stamps the definer to the saving user. If the definer is deleted the schedule stops firing (it never falls back to an admin).
- **Not transactional.** A trigger runs inside a transaction because its pending-run record must be consumed atomically with its effects; a schedule has no run record, so a scheduled procedure that wants atomicity opens its own `db.transaction(...)` — which, unlike inside a trigger, is permitted.
- `args` is the object handed to the procedure as its `args` module, exactly as with [`CALL_PROCEDURE`](#call_procedure). Because a schedule is a separate record from the procedure, one procedure can carry several schedules with different arguments.
- `timeoutMs` bounds one run's wall clock; omit it (or `0`) to use `scheduleTimeoutMs`. Everything else — instruction budget, depth, memory, log caps — comes from the `script*` keys.
- `enabled` defaults to `true`. `ifVersion` is optional optimistic concurrency: present and not equal to the stored version → `409-8`. Use `0` to require that the schedule does not exist yet.
- **Delivery is at-most-once per due instant.** Firing is never finer-grained than `scheduleTickMs`, a run still executing when the next occurrence is due is skipped rather than queued twice, and **missed runs while a node was down are skipped, not caught up** — a job that must not miss an occurrence should be idempotent and driven off data, not off the clock. Under clustering each schedule is owned by exactly one node through the consistent-hash ring, so a schedule fires once per due instant across the cluster and fails over automatically; a membership change during a tick may drop that tick rather than run it twice (see [docs/clustering.md](docs/clustering.md) → *Scheduled procedures*).
- A schedule is stored **with its database**, in `{filePath}/{database}/.schedules/{name}.json`, so dropping the database removes it.
- **Errors**: `403-2` schedules disabled, `403-1` not permitted, `404-4` unknown database, `404-8` unknown procedure, `400-1` an invalid name (3–64 alphanumerics plus `_` and `-`), `400-16` neither or both of `cron`/`intervalMs`, a malformed cron, or a negative `timeoutMs`, `400-17` past `scheduleMaxPerDatabase`, `409-8` version conflict.

#### `DELETE_SCHEDULE`
```json
{"type":"DELETE_SCHEDULE","databaseName":"mydb","name":"nightlyRollup"}
```
Idempotent — succeeds whether or not the schedule existed. A [`DELETE_PROCEDURE`](#delete_procedure) is refused with `400-16` while a schedule still references it (the message names the schedule).

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
`args` is omitted from a listing. `nextRunAt` and `owner` are computed by the answering node — the schedule registry is in-memory and per-node, so both describe that node's view rather than a cluster-wide fact (`owner` is absent when clustering is off). Requires `READ` on the database.

#### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

### Schema validation

A collection can carry a single **JSON Schema (draft 2020-12)** that every write must satisfy. Attach one with [`SAVE_SCHEMA`](#save_schema) and remove it with [`DELETE_SCHEMA`](#delete_schema) (both require admin privileges or database ownership). While a schema is in force, every `SAVE` and `BULK_SAVE` document is validated **before it is committed**, so a non-compliant document never reaches the collection:

- A non-compliant `SAVE` is rejected with `400-7` and the document is not written.
- A `BULK_SAVE` is **all-or-nothing**: if any document violates the schema the whole batch is rejected with `400-7` (the message names the offending `_id`), and nothing is written.
- Collections without a schema are unconstrained (existing behaviour is unchanged).

The reserved `_id` field is **excluded** from validation (it is a system-assigned primary key, already format-checked), so a schema with `"additionalProperties": false` does not need to declare it.

**Supported keywords** (a pragmatic subset of 2020-12): `type` (incl. `integer` vs `number`), `enum`, `const`; object — `properties`, `required`, `additionalProperties`, `patternProperties`, `propertyNames`, `minProperties`/`maxProperties`, `dependentRequired`, `dependentSchemas`; array — `prefixItems`, `items`, `minItems`/`maxItems`, `uniqueItems`, `contains`/`minContains`/`maxContains`; string — `minLength`/`maxLength`/`pattern`; number — `minimum`/`maximum`/`exclusiveMinimum`/`exclusiveMaximum`/`multipleOf`; applicators — `allOf`/`anyOf`/`oneOf`/`not`/`if`/`then`/`else`; and local `$ref` (JSON-Pointer references within the same schema, e.g. `#/$defs/foo`) with `$defs`. `format` is accepted but **non-asserting** (annotation only). Metadata keywords (`$schema`, `$id`, `title`, `description`, …) are accepted; any **unrecognized** keyword is ignored but surfaced as a non-fatal `warning` on the `SAVE_SCHEMA` response. Known-but-unimplemented keywords (`unevaluatedProperties`/`unevaluatedItems`, `$dynamicRef`, remote `$ref`, `$vocabulary`) are **rejected** by `SAVE_SCHEMA` (`400-8`) so a schema author is never misled into believing an unenforced constraint applies.

**Custom (EJson) types.** Beyond the standard `type`, a dedicated `customType` keyword asserts one of the extended types (`geo`, `vector`, `datetime`, `time`), e.g. `{"customType":"geo"}` requires a `#geo(lat,lng)` value. Custom-typed values also satisfy `"type":"string"` (they are stored as strings), but only `customType` distinguishes a geo from an arbitrary string.

Schemas are **user data**: each is stored as `{collection}-schema.json` in the collection's folder and cached in memory so validation adds negligible per-write cost. Under clustering they are replicated to every node (the schema op is coordinator-serialized DDL, re-executed on peers) and reconciled by admin anti-entropy, so a node that was down during a `SAVE_SCHEMA`/`DELETE_SCHEMA` catches up on rejoin.

### Users & Permissions

Every connection must authenticate before sending any protected operation. `LIST_DATABASES`, `AUTHENTICATE`, and `CLOSE_CONNECTION` are the only operations that do not require authentication.

#### `AUTHENTICATE`
```json
{"type":"AUTHENTICATE","username":"Alice","password":"secret"}
```

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
A user can change their own password by providing `currentPassword` for verification. An admin can change any user's password without supplying `currentPassword`. Non-admins cannot change another user's password.

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

#### `GET_DATABASE_STATS` (admin only)
Returns memory usage, totals, and per-database/per-collection breakdown. Useful for monitoring eviction and tuning `maxMemory`.

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
      "forwarded": 118,
      "forwardFallbacks": 3
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

Ownership takes precedence over `databasePermissions` and `collectionPermissions`. A collection-level grant takes precedence over a database-level one. `READ_WRITE` also covers `READ`.

`DROP_DATABASE` requires admin privileges or ownership — the `globalPermissions` field no longer grants the ability to drop databases.

`scriptPermissions` is a **per-database level**, `{"mydb": "MANAGE"}`:

| Level | Allows |
|---|---|
| `NONE` (or absent) | nothing |
| `RUN` | [`RUN_SCRIPT`](#run_script) and [`CALL_PROCEDURE`](#call_procedure) |
| `MANAGE` | everything `RUN` allows, plus installing procedures, triggers and schedules ([`SAVE_PROCEDURE`](#save_procedure), [`DELETE_PROCEDURE`](#delete_procedure), [`SAVE_TRIGGER`](#save_trigger), [`DELETE_TRIGGER`](#delete_trigger), [`SAVE_SCHEDULE`](#save_schedule), [`DELETE_SCHEDULE`](#delete_schedule)) |

Admins and database owners have an implicit `MANAGE` on the databases they reach. A grant on one database says nothing about another. The boolean form written by older clients (`{"mydb": true}`) is still accepted and reads as `RUN`; `false` reads as `NONE`. A value that is neither a boolean nor a level name is rejected with `400-1` rather than read as a denial.

Installing is deliberately its own level rather than something a `READ_WRITE` grant confers. A procedure called through `CALL_PROCEDURE` runs with the **caller's** authority, so whoever installs one hands every higher-privileged caller code to execute — and a **trigger** or a **schedule** runs with the *installer's* authority, which makes installing strictly more powerful than writing.

Being allowed to start a script is separate from what it may do: every operation a script issues is authorized again on its own request against `databasePermissions`/`collectionPermissions`, so a user granted `RUN` plus `READ` can run a script that reads but not one that writes. The one exception is a trigger, which runs as its `definer` — see [`SAVE_TRIGGER`](#save_trigger).

> **Upgrade note.** Roll every node to this version before granting `MANAGE` or otherwise rewriting a user record in a cluster. User records replicate by shipping the record itself, and a node without `scriptPermissions` levels cannot parse the string form — it would skip the whole user record, not just the grant. Note that any write to a user record converts it (a password change will do), so the ordering matters even if you never touch a script grant.

Operations that require `READ`: `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `LISTEN`, `LIST_PROCEDURES`, `LIST_TRIGGERS`, `LIST_SCHEDULES`. A `LISTEN` or `AGGREGATE` that contains a `JOIN` step additionally requires `READ` on each joined collection (in the same database); otherwise the request is rejected with `FORBIDDEN`.  
Operations that require `READ_WRITE`: `SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`, `SAVE_SCHEMA`, `DELETE_SCHEMA` (the last two also being available to database owners and admins, like the other DDL operations).

### Authentication errors

| Situation | `status` | `errorCode` | `message` |
|---|---|---|---|
| Request sent before authenticating | `UNAUTHENTICATED` | `401-1` | `Must authenticate first` |
| Authenticated user was deleted mid-session | `UNAUTHENTICATED` | `401-2` | `User no longer exists` |
| Wrong username or password | `ERROR` | `401-3` | `The user doesn't exist or the wrong credentials have been provided` |
| Insufficient permissions | `FORBIDDEN` | `403-1` | `Action is forbidden, no permissions` |

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
| `401-1` | `UNAUTHENTICATED` | Must authenticate first |
| `401-2` | `UNAUTHENTICATED` | User no longer exists |
| `401-3` | `ERROR` | The user doesn't exist or the wrong credentials have been provided |
| `403-1` | `FORBIDDEN` | Action is forbidden, no permissions |
| `403-2` | `FORBIDDEN` | Script execution is disabled on this server |
| `404-1` | `NOT_FOUND` | User not found |
| `404-2` | `NOT_FOUND` | Entry not found |
| `404-3` | `NOT_FOUND` | No results |
| `404-4` | `NOT_FOUND` | Database not found |
| `404-5` | `NOT_FOUND` | No users found |
| `404-6` | `NOT_FOUND` | No index registered for the specified field |
| `404-7` | `NOT_FOUND` | Listen registration not found |
| `404-8` | `NOT_FOUND` | Procedure not found |
| `404-9` | `NOT_FOUND` | Trigger not found |
| `404-10` | `NOT_FOUND` | Schedule not found |
| `408-1` | `ERROR` | Script exceeded its time budget |
| `409-1` | `ERROR` | User already exists |
| `409-2` | `ERROR` | Database already exists |
| `409-3` | `ERROR` | A transaction is already in progress for this connection |
| `409-4` | `ERROR` | No active transaction for this connection |
| `409-5` | `ERROR` | Could not acquire the collection lock in time; transaction aborted |
| `409-6` | `ERROR` | Operation not allowed while a transaction is open |
| `409-7` | `ERROR` | Transaction aborted: a participant could not prepare |
| `409-8` | `ERROR` | The procedure, trigger or schedule was modified by someone else |
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
| `421-2` | `ERROR` | A transaction may only touch collections owned by a single node |
| `503-1` | `ERROR` | Max number of connections reached |
| `503-2` | `ERROR` | Cluster does not have a write quorum |
| `503-3` | `ERROR` | Timed out waiting for the replication quorum |
| `503-4` | `ERROR` | The collection's owner node is unreachable |
| `503-5` | `ERROR` | Admin coordinator is synchronizing, retry shortly |

### Bootstrap

On first startup, if no admin user exists, the server creates a superadmin from `defaultAdminUsername` / `defaultAdminPassword`. Both are required and validated at startup (see Configuration), so the server always has a way to bootstrap an admin. If an admin user already exists, these values are ignored.

### Configuration

Configuration is read from `lwnrdb.cfg` in the working directory, falling back to bundled defaults for any missing key. Lines starting with `#` and blank lines are ignored, so you can document special cases inline.

Every value is **validated at startup**. If any value is invalid, the server logs a `FATAL` error listing all problems and refuses to start.

**`lwnrdb.cfg` keys:**

| Key | Rule |
|---|---|
| `port` | Valid number, 1–65535 |
| `maxConnections` | Valid number ≥ 0. `0` means unlimited connections |
| `filePath` | Path that exists or can be created, and is writable by the process |
| `logPath` | Path that exists or can be created, and is writable by the process |
| `backgroundProcessingThreads` | Valid number ≥ 1 |
| `maxLogFiles` | Valid number ≥ 1 |
| `maxPageSize` | Human-readable size (e.g. `2Mb`) > 0, and strictly greater than `maxEntrySize` |
| `maxEntrySize` | Human-readable size (e.g. `1Mb`) > 0, and strictly smaller than `maxPageSize` |
| `defaultAdminUsername` | Non-blank string |
| `defaultAdminPassword` | Non-blank string, at least 8 characters |
| `maxMemory` | Human-readable size; `0` (unlimited) and `-1` (caching disabled) are also valid |
| `transactionLockTimeoutMs` | Valid number ≥ 1. Milliseconds a write inside a transaction waits to acquire a busy collection's write lock before the transaction is aborted (`409-5`) |
| `shutdownTimeoutMs` | Valid number ≥ 1 (default `15000`). Total budget for a graceful shutdown — refusing new connections, releasing open transactions, draining the trigger and background-index queues. Work still outstanding when it expires is abandoned with a warning naming what was dropped |
| `tlsEnabled` | `true` or `false`. When `true`, every connection is encrypted and plaintext clients are rejected |
| `tlsKeystorePath` | Path to a PKCS12 keystore. Used only when `tlsEnabled=true`; its parent directory must be writable. If the file is absent a self-signed keystore is generated there |
| `tlsKeystorePassword` | Non-blank string protecting the PKCS12 keystore. Required when `tlsEnabled=true` |
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
| `scriptRoutingEnabled` | `true` or `false` (default `true`). Whether a script ([`RUN_SCRIPT`](#run_script), `CALL_PROCEDURE`) may be forwarded to a live node chosen by current script load instead of running on the node that received it. Set `false` to keep every script on the receiving node. Only a node that is alive **and** caught up on admin metadata is chosen, so a script never lands on a node that has not applied the DDL it depends on. Placement spreads interpreter CPU, not data locality: the chosen node is usually not the owner of the collections the script touches, so each operation still costs a round trip. `scriptsEnabled` and the `script*` sandbox keys must be uniform across the cluster, and every node must be rolled before the first script runs on an upgraded cluster |
| `clusterTlsEnabled` | `true` or `false`. TLS-encrypt the node-to-node channel (reuses the keystore) |
| `clusterSecret` | Non-blank shared secret authenticating the cluster channel. Required when `clusterEnabled=true` |
| `antiEntropyIntervalMs` | Valid number ≥ 1. How often each node runs a background anti-entropy sweep reconciling its collections against live peers |
| `tombstoneRetentionMs` | Valid number ≥ 1. How long delete tombstones are kept before anti-entropy GC; must exceed the longest expected node downtime |
| `scriptTimeZone` | A valid IANA time zone id (e.g. `UTC`, `Europe/Madrid`) or fixed offset. The zone a stored script's `Date`/`Temporal`/`toLocaleString` answers in, so the same script returns the same answer on every node |
| `scriptLocale` | A valid BCP 47 language tag (e.g. `en-US`). The locale a stored script's locale-sensitive formatting and collation use |
| `scriptsEnabled` | `true` or `false` (default `true`). Whether clients may run scripts at all ([`RUN_SCRIPT`](#run_script)), and whether stored procedures may be installed or called. On by default: a script is bounded by the server-fixed sandbox below and by permissions — only an admin, a database owner, or a user holding `scriptPermissions` for that database may start one, and every operation the script issues is authorized again on its own request. When `false` the operations are refused with `403-2` |
| `scriptInstructionBudget` | Valid number ≥ 1. Max interpreter instructions per script run; exceeding it aborts with `400-11` |
| `scriptTimeoutMs` | Valid number ≥ 1. Max wall-clock time per script run; exceeding it aborts with `408-1` |
| `scriptMaxDepth` | Valid number ≥ 1. Max nested call depth per script run; exceeding it aborts with `400-11` |
| `scriptMaxSourceBytes` | Human-readable size > 0. Max accepted script source; larger is rejected with `400-10` |
| `scriptMaxMemoryBytes` | Human-readable size > 0. Max bulk memory a script run may allocate; exceeding it aborts with `400-12`. Bounds the allocations that are proportional to a script-supplied length (a huge `repeat`/`padStart`, a dense array, a typed array, a `join`); ordinary small allocations are bounded by `scriptInstructionBudget` instead |
| `scriptMaxResultBytes` | Human-readable size > 0 (default `16Mb`). Max size of the value a script returns; a larger result fails the run with `400-15` (the `console` output still comes back). Use `db.cursor` to process more data than can be returned |
| `scriptCursorBatchSize` | Valid number ≥ 1 (default `500`). Default number of documents `db.cursor` fetches per batch |
| `scriptCursorMaxBatchSize` | Valid number ≥ 1 (default `5000`), and ≥ `scriptCursorBatchSize`. Upper clamp for a caller-supplied `batchSize`, so one call cannot materialise an unbounded batch |
| `scriptMaxLogLines` | Valid number ≥ 1. Max `console` lines returned with the response (newest kept) |
| `scriptMaxLogLineChars` | Valid number ≥ 1. Max characters kept per returned `console` line |
| `scriptTextImportEnabled` | `true` or `false` (default `false`). Whether a script may evaluate a string as a module through the `script` module's `importText` |
| `procedureCacheSize` | Compiled stored procedures retained per node (>= 0, default `128`); keyed by procedure version, so a save can never serve a stale entry. `0` compiles on every call |
| `procedureCacheMaxBytes` | Human-readable size > 0 (default `32Mb`). Memory bound on cached procedure **source**. Separate from `maxMemory`, which bounds the user document/index cache only |
| `schemaCacheMaxBytes` | Human-readable size > 0 (default `32Mb`). Same contract, for cached collection schemas |
| `triggerCacheMaxEntries` | Collections whose trigger list is kept in memory (>= 0, default `4096`). Bounded by count, not bytes — a trigger list is small and read on every committed write. `0` reads from disk every time |
| `metadataMissCacheMaxEntries` | Remembered “no such procedure/schema” answers (>= 0, default `4096`). Kept apart from the caches above so a caller naming thousands of nonexistent procedures cannot evict the ones in use |
| `triggersEnabled` | `true` or `false` (default `false`). Whether committed writes fire triggers. Separate from `scriptsEnabled` because a trigger runs code with no client asking for it; trigger DDL works either way |
| `triggerThreads` | Workers on the trigger executor (>= 1, default `2`). Its own pool, not the background index queue, so a slow trigger cannot stall field-index maintenance |
| `triggerQueueSize` | Bounded trigger queue (>= 1, default `10000`). On overflow the oldest queued event is dropped with a warning and counted in `GET_DATABASE_STATS` |
| `triggerMaxDepth` | How deep a chain of trigger-fired writes may go (>= 0, default `3`). A trigger with `allowCascade=false` (the default) already fires nothing above depth 0 |
| `triggerTimeoutMs` | Max wall-clock ms a single trigger run may take (>= 1, default `1000`). Tighter than `scriptTimeoutMs` because nobody is waiting on the result |
| `triggerRunLogEnabled` | `true` or `false` (default `true`). Whether a fired trigger is recorded durably before it runs, so a run queued when the process dies is replayed at startup. The run's effects and the consumption of its record commit together, so a replay cannot apply it twice; turning this off trades that for one less admin write per fired trigger. The record is **node-local** — see [docs/clustering.md](docs/clustering.md) → *Pending trigger runs are node-local* |
| `triggerRunRetentionMs` | Valid number ≥ 1 (default `86400000`). How long a pending trigger-run record is kept before it is garbage-collected as stranded — its collection was dropped, or the node that owned it never came back |
| `schedulesEnabled` | `true` or `false` (default `true`). Whether schedules fire. Separate from `scriptsEnabled`/`triggersEnabled`: a scheduled run executes code on a clock, with no client asking for it and with its installer's authority. Leaving it on costs a ticker thread and nothing else until somebody installs a schedule — and installing one requires `scriptsEnabled`, since a schedule can only name a stored procedure. While it is off the three schedule operations answer `403-2` |
| `scheduleThreads` | Valid number ≥ 1 (default `2`). Workers running scheduled procedures. Its own pool rather than the trigger executor's, because a scheduled job may hold a worker for its whole timeout |
| `scheduleQueueSize` | Valid number ≥ 1 (default `100`). Bounded queue of due runs; on overflow the oldest is dropped with a warning and counted, since no client is waiting and the schedule fires again at its next occurrence |
| `scheduleTickMs` | Valid number ≥ 1 (default `1000`). How often the scheduler looks for due schedules. Firing is never finer-grained than this, so a schedule whose `intervalMs` is below the tick fires once per tick |
| `scheduleRefreshMs` | Valid number ≥ 1 (default `60000`). How often the whole schedule registry is rebuilt from disk. The DDL path updates it directly; this is the safety net for schedules that arrived through cluster replication or admin anti-entropy |
| `scheduleTimeoutMs` | Valid number ≥ 1 (default `30000`). Default wall clock for one scheduled run; a schedule may override it with its own `timeoutMs` |
| `scheduleMaxPerDatabase` | Valid number ≥ 1 (default `100`). Cap on schedules per database, so a `SAVE_SCHEDULE` loop cannot make the per-tick scan unbounded |
| `scheduleCacheMaxBytes` | Human-readable size > 0 (default `8Mb`). Memory bound on the cached schedule definitions. Same contract as `procedureCacheMaxBytes`: derived from disk, LRU-evicted, and budgeted **separately** from `maxMemory` |

```
# the port the server listens on
port=8989
# 0 = unlimited connections
maxConnections=100
filePath=db
logPath=logs
maxPageSize=2Mb
maxEntrySize=1Mb
defaultAdminUsername=admin
defaultAdminPassword=administrator
maxMemory=512Mb
# ms a transactional write waits for a busy collection lock before aborting (409-5)
transactionLockTimeoutMs=5000
shutdownTimeoutMs=15000
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
scriptMaxResultBytes=16Mb
scriptCursorBatchSize=500
scriptCursorMaxBatchSize=5000
# stored procedures and triggers; triggers are off by default and gated separately
procedureCacheSize=128
# metadata cache bounds - budgeted SEPARATELY from maxMemory (see below)
procedureCacheMaxBytes=32Mb
schemaCacheMaxBytes=32Mb
triggerCacheMaxEntries=4096
metadataMissCacheMaxEntries=4096
triggersEnabled=false
triggerThreads=2
triggerQueueSize=10000
triggerMaxDepth=3
triggerTimeoutMs=1000
# durable pending trigger runs, so a run queued when the process dies is replayed at startup
triggerRunLogEnabled=true
triggerRunRetentionMs=86400000
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
Schemas and trigger lists — are bounded separately by `procedureCacheMaxBytes`,
`schemaCacheMaxBytes` and `triggerCacheMaxEntries`, and sit on top of it. All three
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
before.

### Clustering (multi-node)

> **Experimental.** Full design, implementation details, and the operations runbook
> live in [docs/clustering.md](docs/clustering.md). With `clusterEnabled=false` (the
> default) the node behaves exactly as a standalone server.

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
clusterSecret=change_this_shared_secret
```

The node-to-node channel uses the same line-delimited JSON transport as clients,
authenticated with `clusterSecret` and optionally encrypted with
`clusterTlsEnabled` (which reuses the PKCS12 keystore machinery — all nodes must
share the same keystore for the TLS cluster channel to establish).

### Memory management

`maxMemory` is the **JVM heap-used budget**: a background sweep (every 5s) drops least-frequently-used user collections/indexes whenever the JVM heap exceeds this value, until heap is back below the budget. Values are human-readable (e.g. `512Mb`, `2Gb`). Two special values are accepted:
- `0` — unlimited; caching is on but eviction never triggers (suitable when `-Xmx` is already the only ceiling you want).
- `-1` — caching disabled; user collections and indexes are always read from disk. Admin collections are always cached regardless.

Eviction order is LFU. Access counts are recorded asynchronously and persisted in the `admin/collection_usage` collection; records older than 24h are pruned hourly. Within the cache, PK indexes are preferred over field indexes, which are preferred over full document maps.

**Aligning RSS with the cap.** `maxMemory` constrains JVM heap usage but cannot reclaim metaspace, JIT code, or committed-but-unused heap. To make Activity Monitor / `top` match the configured budget, set `-Xmx` close to `maxMemory`. Startup logs a warning when `-Xmx > maxMemory × 2`.

**Streaming reads.** Queries no longer load an entire collection into memory before filtering. When a `FILTER` step matches against an index, only the matched entries are fetched via positioned reads (the whole collection is never loaded). When there is no usable index, the collection is scanned page-by-page: one page is resident at a time, the page-size estimate from the `admin/pages/<db>_<collection>` metadata drives a between-pages headroom check that evicts other cached resources when the budget is tight, and consumed pages are released for GC.

`SORT`, `GROUP_BY`, `JOIN`, and `DISTINCT` also use a single-field index when one exists on the step's field **and** the step is the pipeline source (no earlier step has already produced a stream). The field index maps each value to its matching ids, so the step works from that grouping instead of scanning the whole collection: `DISTINCT` reads no documents at all (the index keys are the distinct values), `GROUP_BY`/`SORT` fetch only the grouped/ordered documents via positioned reads, and `JOIN` fetches only the remote documents whose value matches a local value. Because indexes model scalar/custom/null values only, documents whose indexed field holds a JSON object or array are outside index scope and do not appear in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results; run the step on a non-indexed field if you need those included. When no index applies, these steps still materialize their working set in memory as before.

> **Note — an index buys `GROUP_BY` little (often nothing).** Unlike `DISTINCT`/`FILTER`/index-only `COUNT`, an index-backed `GROUP_BY` cannot avoid reading documents: its output places every grouped document into a `group` array, so every matched document is still fetched. The index only supplies the value→ids buckets, which replaces a cheap in-memory `groupingBy` — a negligible saving. On a field present in (nearly) every document this yields **effectively no speed-up** over a plain scan, and is in fact slightly *worse*: the index path fetches via random positioned reads and materializes the whole working set (the id set, a `docById` map and the grouped list) in memory, whereas the scan path streams page-by-page with the between-pages headroom check. The fast-path helps **only when the grouped field is sparse** (few documents carry it) — there, like `FILTER`, it reads just the matching documents instead of every page. The index fast-path is kept for that sparse case, but `GROUP_BY` should not be expected to benefit from an index the way the other steps do.
Object- and array-valued fields are covered only by the **element-match hash indexes** used at the `FILTER` step (`EQUALS`/`NOT_EQUALS`/`IN`/`NOT_IN`), stored in separate `…-Object.idx` / `…-Array.idx` files; those hashes cannot be reconstructed or ordered, so `GROUP_BY`/`SORT`/`DISTINCT` still skip object/array values.

A `COUNT` (with the collection as the pipeline source) is answered from the indexes alone — without reading any documents — whenever every step before it either filters via an index or leaves the document count unchanged. `FILTER` steps are resolved to id-sets through their indexes; sequential filters compose as `AND`, so the count is the size of the **intersection** of their id-sets. A single indexed field operator resolves through its field index; a conjunction resolves when every leaf is index-resolvable, combining the per-leaf id-sets with set algebra (`AND` = intersection, `OR` = union, `XOR` = exactly-one, and `NOR`/`NAND` = the complement against the full id universe taken from the PK index). Count-preserving steps between the filters and the `COUNT` — `MAP`, `JOIN`, `SORT` — are skipped entirely (they emit one row per input row, and `COUNT` discards their transformed output; `JOIN` permissions are still checked before execution). A `FILTER` is only index-resolvable while it still sees the stored documents, so once a `MAP`/`JOIN` has modified them no later `FILTER` can use its index. If a step changes the count in a data-dependent way (`GROUP_BY`, `DISTINCT`, `LIMIT`, `SKIP`), or any leaf lacks a usable index, the count falls back to counting the filtered stream as before.

### Concurrency & locking

Locking is two-tier and applies to **both reads and writes** (earlier versions locked only writes):

- **Collection-level read/write locks.** Each collection (and each field index) has a read/write lock. Reads acquire a *shared* read lock; writes (`SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`) acquire an *exclusive* write lock. While a writer holds a collection, nobody else may read or write it; multiple readers run concurrently. An `AGGREGATE` with `JOIN` steps read-locks the primary collection and every joined collection, acquiring them in a deterministic order so overlapping queries cannot deadlock. Cache eviction only evicts a resource it can exclusively (write) lock, so it never races an in-flight read or write.
- **File-level read/write locks.** Below the collection tier, each physical `.dat`/`.idx` file has its own read/write lock, so a file's bytes are never read while they are being rewritten.

**Dirty reads.** Read operations (`FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `LIST_USERS`) accept an optional `"dirtyRead": true` (default `false` = fully locked). A dirty read **skips the collection-level read lock**, so it can proceed even while a long write holds the collection. It still goes through the file-level read locks, so every page/index file it reads is individually valid (never half-written). A dirty read may observe a mix of pre- and post-write pages across a collection; that is the trade-off for not waiting.

**Transactions.** A connection can open a transaction (`START_TRANSACTION`) to make several writes atomic (see [Transactions](#transactions)). Writes inside a transaction are buffered in the internal `admin/transactions` collection rather than applied, and become visible to the real collections only on `COMMIT_TRANSACTION`; `ROLLBACK_TRANSACTION` discards them. Locking is **lazy**: the first buffered write to a collection acquires that collection's exclusive write lock (waiting up to `transactionLockTimeoutMs`, then aborting the transaction with `409-5` so two concurrent transactions can never deadlock) and holds it — on the connection's own virtual thread — until commit/rollback. 
While held, other connections cannot read or write those collections, which is what keeps the committed batch atomic. The transaction's own reads apply its buffered mutations (**read-your-writes**); there is no snapshot isolation against other connections beyond that write-exclusivity. Committing replays the buffered operations through the normal write path, so field-index maintenance, page metadata and `LISTEN` notifications behave exactly as for ordinary writes. If the connection drops with a transaction open it is auto-rolled-back, and any operation records left behind by a crash are cleared at startup.

## Q&A

- I want X feature. Can you add it for me?
  - If it feasible and it makes sense, sure! But it might take some time. Please submit an issue
- Can I use it in production?
  - I wouldn't recommend doing that at least for now, as it is very experimental
- I discovered a bug. What should I do?
  - Please submit an issue with the steps to reproduce it and the expected result.

## Code quality

Formatting and static analysis run as part of `mvn verify`, so a violation
fails the build (and therefore blocks a merge). All four tools are wired into
the `verify` phase:

| Tool | Goal | Config |
|---|---|---|
| **Spotless** (Eclipse JDT formatter, 4-space) | `spotless:check` | [`config/eclipse-format.xml`](config/eclipse-format.xml) |
| **Checkstyle** | `checkstyle:check` | [`config/checkstyle.xml`](config/checkstyle.xml) |
| **PMD** | `pmd:check` | [`config/pmd-ruleset.xml`](config/pmd-ruleset.xml) |
| **SpotBugs** | `spotbugs:check` | [`config/spotbugs-exclude.xml`](config/spotbugs-exclude.xml) |

To auto-format your changes before committing:

```bash
mvn spotless:apply
```

> **Build JDK:** use **JDK 25** (matching the project's compiler target and CI).
> JDK 26 also works. The Eclipse JDT formatter is used instead of
> Google/Palantir formatters specifically because the latter rely on `javac`
> internals that are incompatible with JDK 25/26.

The linter rulesets are deliberately curated rather than using defaults: they
target real defects and conventions the formatter does not cover, while
accommodating the project's intentional choices (e.g. the `_id` wire field,
`snake_case` test method names, `null`-as-absent sentinels for index/config
lookups). Exclusions are documented inline in each config file.

## Contributing

Pull requests are welcome! For major changes, please open an issue first
to discuss what you would like to change.

Please make sure to update tests as appropriate and add new ones if a new feature is developed or a big bug solved.
