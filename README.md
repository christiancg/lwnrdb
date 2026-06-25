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
- Each collection is split across pages (one file per page) sized up to `maxPageSize`; admin metadata about a collection's pages lives in a parallel paged collection under `admin/pages_<collection>`, and the pagination of that admin collection is itself persisted under `admin/pages_pages_<collection>` (further levels are tracked in memory only and rebuilt at startup). New inserts use a first-fit search across existing pages, so space freed by deletions is reused before a new page is allocated.

## Pending tasks

- [ ] Geo type support
  - [ ] Distance operator
  - [ ] Within operator
- [ ] Vector type support
  - [ ] Semantic search
- [ ] Transactions
- [ ] Replication between nodes (no master-slave arch; all nodes are equal; no sharding)
- [ ] Stored procedures
- [ ] Jobs
- [ ] Listenable queries (you create the query and then the DB sends events when there are changes)
- [ ] Standardized error messages with error code, following HTTP patterns: 4xx → user error, 5xx → server error, ending with a specific number per error. Ie 401-1 "need to authenticate"
- [ ] Explain / Analyze with index and query suggestions
- [ ] Integration tests for all possible API commands, including aggregations
- [ ] Admin operation to rebuild indexes
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

All messages are line-delimited JSON sent over a TCP connection. Every request must include a `type` field. Responses always contain `type`, `status` (`OK`, `ERROR`, `NOT_FOUND`), and `message`.

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
Object- and array-valued fields are instead indexed for **element-match** (whole-value equality): a `FILTER` with `EQUALS`, `NOT_EQUALS`, `IN`, or `NOT_IN` hashes the object/array and resolves it through a dedicated per-kind hash index (`…-Object.idx` / `…-Array.idx`). Ordering/containment operators (`GREATER_THAN*`, `SMALLER_THAN*`, `CONTAINS`) and the reconstructing steps above cannot use these hash indexes because a hash cannot be ordered or turned back into a value.

**Field operator types:** `EQUALS`, `NOT_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`, `SMALLER_THAN`, `SMALLER_THAN_EQUALS`, `IN`, `NOT_IN`, `CONTAINS`

**Conjunction operator types:** `AND`, `OR`, `NOR`, `XOR`, `NAND`

#### `CREATE_INDEX`
```json
{"type":"CREATE_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

#### `DROP_INDEX`
```json
{"type":"DROP_INDEX","databaseName":"my_db","collectionName":"my_coll","fieldName":"email"}
```

#### `CLOSE_CONNECTION`
```json
{"type":"CLOSE_CONNECTION"}
```

### Users & Permissions

Every connection must authenticate before sending any protected operation. `LIST_DATABASES`, `AUTHENTICATE`, and `CLOSE_CONNECTION` are the only operations that do not require authentication.

#### `AUTHENTICATE`
```json
{"type":"AUTHENTICATE","username":"Alice","password":"secret"}
```

#### `CREATE_USER` (admin only)
`globalPermissions`, `databasePermissions`, and `collectionPermissions` are all optional (default to empty). Collection permission keys must be in `database|collection` format.
```json
{
  "type": "CREATE_USER",
  "username": "bob",
  "password": "secret1234",
  "admin": false,
  "globalPermissions": ["CREATE_DATABASE"],
  "databasePermissions": {"ordersDb": "READ_WRITE"},
  "collectionPermissions": {"analyticsDb|events": "READ"}
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
  "collectionPermissions": {}
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
| `databasePermissions`   | Grants `READ` or `READ_WRITE` to all collections in a database                        |
| `collectionPermissions` | Grants `READ` or `READ_WRITE` to a specific `database\|collection`                    |

Ownership takes precedence over `databasePermissions` and `collectionPermissions`. A collection-level grant takes precedence over a database-level one. `READ_WRITE` also covers `READ`.

`DROP_DATABASE` requires admin privileges or ownership — the `globalPermissions` field no longer grants the ability to drop databases.

Operations that require `READ`: `FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`. An `AGGREGATE` that contains a `JOIN` step additionally requires `READ` on each joined collection (in the same database); otherwise the request is rejected with `FORBIDDEN`.  
Operations that require `READ_WRITE`: `SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`.

### Authentication errors

| Situation | `status` | `message` |
|---|---|---|
| Request sent before authenticating | `UNAUTHENTICATED` | `Must authenticate first` |
| Wrong username or password | `ERROR` | `The user doesn't exist or the wrong credentials have been provided` |
| Insufficient permissions | `FORBIDDEN` | `action is forbidden, no permissions` |

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
| `tlsEnabled` | `true` or `false`. When `true`, every connection is encrypted and plaintext clients are rejected |
| `tlsKeystorePath` | Path to a PKCS12 keystore. Used only when `tlsEnabled=true`; its parent directory must be writable. If the file is absent a self-signed keystore is generated there |
| `tlsKeystorePassword` | Non-blank string protecting the PKCS12 keystore. Required when `tlsEnabled=true` |

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
# TLS: when enabled, plaintext clients are rejected at the handshake
tlsEnabled=false
tlsKeystorePath=certs/lwnrdb.p12
tlsKeystorePassword=change_it
```

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

### Memory management

`maxMemory` is the **JVM heap-used budget**: a background sweep (every 5s) drops least-frequently-used user collections/indexes whenever the JVM heap exceeds this value, until heap is back below the budget. Values are human-readable (e.g. `512Mb`, `2Gb`). Two special values are accepted:
- `0` — unlimited; caching is on but eviction never triggers (suitable when `-Xmx` is already the only ceiling you want).
- `-1` — caching disabled; user collections and indexes are always read from disk. Admin collections are always cached regardless.

Eviction order is LFU. Access counts are recorded asynchronously and persisted in the `admin/collection_usage` collection; records older than 24h are pruned hourly. Within the cache, PK indexes are preferred over field indexes, which are preferred over full document maps.

**Aligning RSS with the cap.** `maxMemory` constrains JVM heap usage but cannot reclaim metaspace, JIT code, or committed-but-unused heap. To make Activity Monitor / `top` match the configured budget, set `-Xmx` close to `maxMemory`. Startup logs a warning when `-Xmx > maxMemory × 2`.

**Streaming reads.** Queries no longer load an entire collection into memory before filtering. When a `FILTER` step matches against an index, only the matched entries are fetched via positioned reads (the whole collection is never loaded). When there is no usable index, the collection is scanned page-by-page: one page is resident at a time, the page-size estimate from the `admin/pages_<collection>` metadata drives a between-pages headroom check that evicts other cached resources when the budget is tight, and consumed pages are released for GC.

`SORT`, `GROUP_BY`, `JOIN`, and `DISTINCT` also use a single-field index when one exists on the step's field **and** the step is the pipeline source (no earlier step has already produced a stream). The field index maps each value to its matching ids, so the step works from that grouping instead of scanning the whole collection: `DISTINCT` reads no documents at all (the index keys are the distinct values), `GROUP_BY`/`SORT` fetch only the grouped/ordered documents via positioned reads, and `JOIN` fetches only the remote documents whose value matches a local value. Because indexes model scalar/custom/null values only, documents whose indexed field holds a JSON object or array are outside index scope and do not appear in index-backed `GROUP_BY`/`SORT`/`DISTINCT` results; run the step on a non-indexed field if you need those included. When no index applies, these steps still materialize their working set in memory as before.

> **Note — an index buys `GROUP_BY` little (often nothing).** Unlike `DISTINCT`/`FILTER`/index-only `COUNT`, an index-backed `GROUP_BY` cannot avoid reading documents: its output places every grouped document into a `group` array, so every matched document is still fetched. The index only supplies the value→ids buckets, which replaces a cheap in-memory `groupingBy` — a negligible saving. On a field present in (nearly) every document this yields **effectively no speed-up** over a plain scan, and is in fact slightly *worse*: the index path fetches via random positioned reads and materializes the whole working set (the id set, a `docById` map and the grouped list) in memory, whereas the scan path streams page-by-page with the between-pages headroom check. The fast-path helps **only when the grouped field is sparse** (few documents carry it) — there, like `FILTER`, it reads just the matching documents instead of every page. The index fast-path is kept for that sparse case, but `GROUP_BY` should not be expected to benefit from an index the way the other steps do.
Object- and array-valued fields are covered only by the **element-match hash indexes** used at the `FILTER` step (`EQUALS`/`NOT_EQUALS`/`IN`/`NOT_IN`), stored in separate `…-Object.idx` / `…-Array.idx` files; those hashes cannot be reconstructed or ordered, so `GROUP_BY`/`SORT`/`DISTINCT` still skip object/array values.

A `COUNT` (with the collection as the pipeline source) is answered from the indexes alone — without reading any documents — whenever every step before it either filters via an index or leaves the document count unchanged. `FILTER` steps are resolved to id-sets through their indexes; sequential filters compose as `AND`, so the count is the size of the **intersection** of their id-sets. A single indexed field operator resolves through its field index; a conjunction resolves when every leaf is index-resolvable, combining the per-leaf id-sets with set algebra (`AND` = intersection, `OR` = union, `XOR` = exactly-one, and `NOR`/`NAND` = the complement against the full id universe taken from the PK index). Count-preserving steps between the filters and the `COUNT` — `MAP`, `JOIN`, `SORT` — are skipped entirely (they emit one row per input row, and `COUNT` discards their transformed output; `JOIN` permissions are still checked before execution). A `FILTER` is only index-resolvable while it still sees the stored documents, so once a `MAP`/`JOIN` has modified them no later `FILTER` can use its index. If a step changes the count in a data-dependent way (`GROUP_BY`, `DISTINCT`, `LIMIT`, `SKIP`), or any leaf lacks a usable index, the count falls back to counting the filtered stream as before.

### Concurrency & locking

Locking is two-tier and applies to **both reads and writes** (earlier versions locked only writes):

- **Collection-level read/write locks.** Each collection (and each field index) has a read/write lock. Reads acquire a *shared* read lock; writes (`SAVE`, `BULK_SAVE`, `DELETE`, `CREATE_COLLECTION`, `DROP_COLLECTION`, `CREATE_INDEX`, `DROP_INDEX`) acquire an *exclusive* write lock. While a writer holds a collection, nobody else may read or write it; multiple readers run concurrently. An `AGGREGATE` with `JOIN` steps read-locks the primary collection and every joined collection, acquiring them in a deterministic order so overlapping queries cannot deadlock. Cache eviction only evicts a resource it can exclusively (write) lock, so it never races an in-flight read or write.
- **File-level read/write locks.** Below the collection tier, each physical `.dat`/`.idx` file has its own read/write lock, so a file's bytes are never read while they are being rewritten.

**Dirty reads.** Read operations (`FIND_BY_ID`, `AGGREGATE`, `LIST_COLLECTIONS`, `LIST_USERS`) accept an optional `"dirtyRead": true` (default `false` = fully locked). A dirty read **skips the collection-level read lock**, so it can proceed even while a long write holds the collection. It still goes through the file-level read locks, so every page/index file it reads is individually valid (never half-written). A dirty read may observe a mix of pre- and post-write pages across a collection; that is the trade-off for not waiting. Logical read-your-writes consistency against asynchronous background index updates is out of scope (it belongs to the pending *Transactions* work).

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
