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
- Each collection is split across pages (one file per page) sized up to `maxPageSize`; admin metadata about a collection's pages lives in a parallel paged collection under `admin/pages/<db>_<collection>`, and the pagination of that admin collection is itself tracked in memory only and rebuilt at startup (no `pages_pages_*` files on disk). New inserts use a first-fit search across existing pages, so space freed by deletions is reused before a new page is allocated. A delete gives its page's space back too: once a quarter of the page is dead it vacuums the page, and the delete of its last live document truncates the page file to 0 bytes (the file and its page row stay, so the next insert lands there).

## Status

LWNRDB is feature-complete for what it set out to be, and experimental in the sense the
Q&A below means: a learning exercise, not something to put production data in. To try it, follow the [Quickstart](QUICKSTART.md).

**What it does.** Documents are stored as newline-delimited JSON across paged files, queried
through an [aggregation pipeline](docs/protocol.md#aggregate) (the one exception is [`FIND_BY_ID`](docs/protocol.md#find_by_id))
with single-field indexes the planner combines freely, and reachable over a [line-delimited
JSON socket protocol](docs/protocol.md) — optionally [TLS](docs/operations.md#tls--secure-connections). On top of that:

| Area | |
|---|---|
| Types | documents, arrays, dates/times, [geo](docs/protocol.md#custom-operators-type-specific) (distance, within) and [vector](docs/protocol.md#custom-operators-type-specific) (semantic search) |
| Querying | [explain/analyze](docs/protocol.md#explain--analyze) with index suggestions, [listenable queries](docs/protocol.md#listen), [dirty reads](#concurrency--locking), index-only `COUNT` |
| Integrity | [per-collection JSON Schema](docs/protocol.md#schema-validation), [transactions](docs/protocol.md#transactions), [users and permissions](docs/protocol.md#users--permissions) |
| Scripting | a built-in JS engine — [ad-hoc scripts](docs/protocol.md#run_script), [stored procedures](docs/protocol.md#save_procedure), [triggers](docs/protocol.md#save_trigger) (after and before the write), [scheduled procedures](docs/protocol.md#save_schedule), [pipeline operators](docs/protocol.md#script-operators-simplejs-in-the-pipeline), and shared code via `import … from "procedures/<name>"`. See [docs/simplejs.md](docs/simplejs.md) |
| Clustering | fully-replicated nodes, no master, per-collection ownership, quorum writes, anti-entropy, cross-node transactions and script placement. See [docs/clustering.md](docs/clustering.md) |
| Operations | [`SERVER_INFO`](docs/protocol.md#server_info) and a Docker health check, [`GET_DATABASE_STATS`](docs/protocol.md#get_database_stats), [`REINDEX`](docs/protocol.md#reindex), [standardized error codes](docs/protocol.md#error-codes), [Prometheus metrics](docs/operations.md#metrics) (opt-in), memory-bounded caching, graceful shutdown. See [docs/operations.md](docs/operations.md) |

**Known non-goals.** No compound indexes (a pipeline uses as many single-field indexes as
apply), no sharding (every node holds a complete copy), no compression, and no attempt to
compete on raw IOPS.

**Still open.** Widening the JS engine's host surface as new database features need it — the
engine itself is closed (see [docs/simplejs.md](docs/simplejs.md) → *Engine status*).

## Wire protocol

Clients send line-delimited JSON requests over TCP (optionally TLS) and the server answers each with one JSON line. The
full reference is [docs/protocol.md](docs/protocol.md): framing, the request and response envelopes, how replies are
matched to requests, retry rules, then every [operation](docs/protocol.md#operations),
[permission](docs/protocol.md#users--permissions) and [error code](docs/protocol.md#error-codes).

## Operations

Running the server for other people is covered in [docs/operations.md](docs/operations.md): a
[deployment checklist](docs/operations.md#deployment-checklist), [running](docs/operations.md#running-the-server) it on a
host or in Docker, every [configuration key](docs/operations.md#configuration), [sizing](docs/operations.md#sizing),
[security hardening](docs/operations.md#security-hardening), [metrics and alerts](docs/operations.md#monitoring),
[logs](docs/operations.md#logs), [backups](docs/operations.md#backups), [durability](docs/operations.md#durability) and a
[recovery runbook](docs/operations.md#recovery-runbook).

## Using the server

### Quickstart

[QUICKSTART.md](QUICKSTART.md) takes you from nothing to a running server, a first document saved and read back from
the command line, and a first query from one of the client libraries.

### Client (shell, exec, dump/restore)

The server jar is also its own client. When the first argument starts with `--`, it connects to a running server,
local or remote, instead of starting one:

```bash
java -jar lwnrdb.jar --shell   [connection]
java -jar lwnrdb.jar --exec '<json>' [--exec '<json>' ...] [connection]
java -jar lwnrdb.jar --exec [connection] < commands.jsonl
java -jar lwnrdb.jar --dump [--databases a,b | --collections db.coll,...] [--compress] [connection] > backup.ldump
java -jar lwnrdb.jar --restore [connection] < backup.ldump
java -jar lwnrdb.jar --health [connection]
```

| Connection flag | Default | |
|---|---|---|
| `--host <host>` | `127.0.0.1` | |
| `--port <port>` | `8989` | |
| `--user <user>` | `admin` | |
| `--password <password>` | | or the `LWNRDB_PASSWORD` environment variable; `--shell` on a terminal prompts for it |
| `--tls` | off | TLS, verified against the JVM trust store with hostname verification |
| `--insecure` | off | with `--tls`, trust any certificate, e.g. the server's dev-only self-signed one; the client prints a `WARNING: --insecure is active` line on standard error, since anyone on the network path can then read the password and the data |

- **`--shell`** prints a `lwnrdb> ` prompt, sends each line (one JSON request) as it is and pretty-prints the reply.
  `exit`, `quit` or end of input ends the session. There is no line editing, history or multi-line input.
- **`--exec`** sends each `--exec` value, or each line of standard input when no value is given, and prints every
  reply on its own line, exactly as the server sent it: the reply is streamed to standard output rather than parsed,
  so a result of any size needs only a small client heap. It stops at the first reply that is not `OK`.
- **`--dump`** writes a backup to standard output and progress (`shop.orders: 12000 documents`) to standard error.
  `--compress` gzips the stream. Documents are read in pages of about 4 MB: the first page asks for 1,000
  documents and each next page sizes itself from the previous page's bytes per document (at most 100,000 documents).
- **`--restore`** reads a backup from standard input, plain or gzipped (detected by its first two bytes), and prints
  `restored N requests` to standard error every 1000 requests and at the end.
- **`--health`** sends [`SERVER_INFO`](docs/protocol.md#server_info) without authenticating, so it needs no password, prints the
  reply and exits `0` when the state is `READY` and `1` for any other state.

Exit codes: `0` success; `1` a command answered non-OK, the dump or restore stopped (the restore names the line), or
`--health` found the server not `READY`;
`2` usage error; `3` the server cannot be reached, TLS failed, or authentication was refused. The client never reads
`lwnrdb.cfg` and never creates `db/` or `logs/` in its working directory.

The client waits at most 10 seconds to connect and finish the TLS handshake, and at most 10 minutes for each reply;
a server silent for longer ends the run with `no reply from the server within 600 s` (exit code `3`). A reply the
client parses (`--shell`, the dump, the restore, authentication) is capped at 256 MB: a longer one ends the run with
`the server's reply is longer than 268435456 bytes` (exit code `1`). `--exec` streams its replies and has no such cap.
A dump page whose reply passes the cap, or that is still refused with `503-17` after the retries, is asked for again
at half the size after the same `_id`, down to one document; only a single document larger than the cap stops the
dump, naming the `_id` it came after. The client retries `503-2`, `503-4`, `503-5`, `503-9`, `503-10` and `503-16` through
`503-22` up to five times with a growing pause, because each means nothing was applied. It never resends after
`503-3`: that write committed on the node it reached, and resending a `SAVE` without an `_id` would store it twice.

**The dump format is the restore script.** Every line is one ordinary request: `CREATE_DATABASE`,
`CREATE_COLLECTION`, `BULK_SAVE` (at most 4 MB of documents per line), `SAVE_SCHEMA`, `CREATE_INDEX` (one line per
collection, naming every indexed field in `fieldNames`, so a restore builds them in one pass),
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

**Each database is dumped from one snapshot, while writes go on.** Before it reads a database's documents the dump
opens a [snapshot](docs/protocol.md#snapshots-start_snapshot-read_snapshot-release_snapshot) of it, a cut that falls between two
transactions across all of its collections: a document written after the cut is dumped as it was at the cut, one deleted
after it is still dumped, and one inserted after it is left out. Nothing waits for the dump beyond that moment, and
writers keep the old versions it needs only while it runs. A snapshot is aborted when the old versions it keeps pass
`snapshotMaxBytes`, when one of its collections or its database is dropped, or by a `REINDEX` with `"primaryKey": true`;
the dump then fails (exit code 1) rather than mix two cuts, and can simply be run again. Schemas, indexes, procedures,
triggers and schedules are read as they are when the dump reaches them, not from the snapshot.

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

### Client libraries

Official client libraries live under [`clients/`](clients/README.md), one folder per language, all tested against the
shared protocol contract in [`clients/protocol`](clients/protocol/README.md).

- **Java** (Java 17+): `io.github.christiancg:lwnrdb-client` on Maven Central, for Maven and Gradle. Connection pool,
  fluent query builder, transactions, live queries, POJO mapping and the custom value types. See
  [`clients/java`](clients/java/README.md).
- **Spring Data and Spring Boot**: `lwnrdb-spring-boot-starter` adds repositories with derived queries,
  `@Transactional` and a health indicator, configured from `lwnrdb.*` properties.
- **TypeScript / JavaScript** (Node.js 22.12+): `lwnrdb-client` on npm, ESM with bundled types, usable from
  `require` too, with no runtime dependencies. The same pool, query builder, transactions, live queries and custom value
  types. See [`clients/typescript`](clients/typescript/README.md).

```java
try (LwnrdbClient client = LwnrdbClient.connect(ClientSettings.builder()
        .host("localhost", 8989).credentials("admin", password).build())) {
    List<Document> active = client.database("shop").collection("orders").query()
            .filter(field("status").eq("active")).sort("total", Order.DESC).limit(10).toList();
}
```

```ts
await using client = await LwnrdbClient.connect({ credentials: { username: "admin", password } });
const active = await client.database("shop").collection("orders").query()
    .filter(field("status").eq("active")).sort("total", Order.DESC).limit(10).toArray();
```

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

### Query execution

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

**Transactions.** A connection can open a transaction (`START_TRANSACTION`) to make several writes atomic (see [Transactions](docs/protocol.md#transactions)). Writes inside a transaction are buffered in the internal `admin/transactions` collection rather than applied, and become visible to the real collections only on `COMMIT_TRANSACTION`; `ROLLBACK_TRANSACTION` discards them. Locking is **lazy**: the first buffered write to a collection acquires that collection's exclusive write lock (waiting up to `transactionLockTimeoutMs`, then aborting the transaction with `409-5` so two concurrent transactions can never deadlock) and holds it — on the connection's own virtual thread — until commit/rollback. 
While held, other connections cannot read or write those collections, which is what keeps the committed batch atomic. The transaction's own reads apply its buffered mutations (**read-your-writes**); there is no snapshot isolation against other connections beyond that write-exclusivity. Committing replays the buffered operations through the normal write path, so field-index maintenance, page metadata and `LISTEN` notifications behave exactly as for ordinary writes. If the connection drops with a transaction open it is auto-rolled-back, and so is one left idle for `transactionIdleTimeoutMs` (the next request then answers `409-16` once). After a crash, a transaction that had not reached its commit point is discarded at startup, and one that had is finished.

### Consistency guarantees and known limitations

What a single node promises, and what it deliberately does not. The multi-node model is described in [docs/clustering.md](docs/clustering.md) → *What the cluster actually guarantees*.

**Guaranteed**

- **An acknowledged write survives a process crash.** Writes go to the files before the response is sent, so a `kill -9` or a JVM crash loses nothing that was acknowledged.
- **An acknowledged write survives a power loss** while `syncWrites` is on (the default). Every write is first appended to its collection's write-ahead log as the exact bytes it puts at each offset of each page and of `pk.idx`, and that record is synced to the device before the pages and `pk.idx` are touched; a created, renamed or deleted file has its folder synced too. Startup replays the log over whatever the files hold, so a power loss leaves every acknowledged write and no unacknowledged one. On a platform that cannot sync a directory the server logs a DURABILITY WARNING at startup and runs with `syncWrites=false`. See [Durability](docs/operations.md#durability) for what it costs.
- **A torn write heals at startup.** A half-written trailing line in a page, `pk.idx`, field-index log or tombstone file is skipped and the file rewritten without it. Page-occupancy metadata is rebuilt from the live bytes `pk.idx` names. An update or delete never moves another record: it appends the new copy, appends its `pk.idx` row, then blanks the old copy into a hole, and the whole write is one record in the collection's write-ahead log (`.wal`), logged before any of it. Startup replays every logged record and cuts the bytes past a logged end, so an interrupted write is either whole or absent. An interrupted page vacuum, which rewrites a page outside the log, brackets the rewrite with two log frames (`REWRITE_BEGIN` before it, `REWRITE_END` once the new page is in place), so replay never writes an older record's bytes into the rewritten page, keeps an intent in the collection's `.intents` log and re-derives the rows of the page it rewrote. That step needs a readable `pk.idx`; when it is not, startup logs the intent and retries at the next start, and [`REINDEX`](docs/protocol.md#reindex) with `"primaryKey": true` settles it and rebuilds the `pk.idx` from the pages.
- **Index and scan answer identically.** Field-index maintenance is asynchronous, but an index-backed read reconciles the documents not yet indexed, and an index that does not cover every document declines so the query scans. The one exception is vector `nearest` (below).
- **Transactions are atomic and write-exclusive**, with read-your-writes inside the transaction and no snapshot isolation outside it (see [Concurrency & locking](#concurrency--locking)). A crash never leaves half a transaction applied.
- **After-triggers are not lost to a crash** while `triggerRunLogEnabled` is on (the default): a run is recorded durably before it runs and replayed at startup, and its effects commit together with the consumption of that record, so a replay cannot apply them twice. A run that keeps failing is retried and then dead-lettered, never dropped silently.
- **A dump is one consistent cut of each database.** [`--dump`](#client-shell-exec-dumprestore) reads every database from a snapshot taken between two transactions, while writes go on: no document that existed throughout is missed, and no write after the cut appears. A snapshot that passes `snapshotMaxBytes` fails the dump instead of producing a mixed one.

**Not guaranteed, by design**

- **Device durability with `syncWrites=false`.** A write is then acknowledged once the operating system has it, so a power loss or an operating-system crash can lose recently acknowledged writes; the server says so in a startup warning. Turn it off only on storage with power-loss protection, or in a [cluster](#clustering-multi-node) where a second node holds the write.
- **Field indexes after a crash with index work pending.** Work for writes still in the collection's write-ahead log is re-queued at startup and needs nothing from you. Work the log no longer covers (a checkpoint dropped the records while it was pending, or it came from a startup heal or a `REINDEX` with `"primaryKey": true`) can miss entries, so an index-backed query can omit those documents until a full [`REINDEX`](docs/protocol.md#reindex). Startup logs a warning naming each collection in that state, and the `-indexes.unclean` marker behind it stays until a `REINDEX` rebuilds every registered index of the collection.
- **Exact vector search by default.** `nearest` is an approximate (ANN) search unless `"exact":true` is passed (see [custom operators](docs/protocol.md#custom-operators-type-specific)).
- **Catching up on schedules.** A scheduled procedure fires at most once per due instant, and runs missed while the node was down are skipped (see [`SAVE_SCHEDULE`](docs/protocol.md#save_schedule)).
- **Isolation for dirty reads.** A read with `"dirtyRead":true` can observe a write half-way through (see [Concurrency & locking](#concurrency--locking)).

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

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.
The details (how to build, what the build checks, and how a release is cut) are in [CONTRIBUTING.md](CONTRIBUTING.md).
Security problems go through [SECURITY.md](SECURITY.md) instead of a public issue.

## License

MIT. Use it, change it, do whatever you want with it, just keep my name on it. See [LICENSE](LICENSE).
