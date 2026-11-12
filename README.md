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
JSON socket protocol](docs/protocol.md) — optionally [TLS](#tls--secure-connections). On top of that:

| Area | |
|---|---|
| Types | documents, arrays, dates/times, [geo](docs/protocol.md#custom-operators-type-specific) (distance, within) and [vector](docs/protocol.md#custom-operators-type-specific) (semantic search) |
| Querying | [explain/analyze](docs/protocol.md#explain--analyze) with index suggestions, [listenable queries](docs/protocol.md#listen), [dirty reads](#concurrency--locking), index-only `COUNT` |
| Integrity | [per-collection JSON Schema](docs/protocol.md#schema-validation), [transactions](docs/protocol.md#transactions), [users and permissions](docs/protocol.md#users--permissions) |
| Scripting | a built-in JS engine — [ad-hoc scripts](docs/protocol.md#run_script), [stored procedures](docs/protocol.md#save_procedure), [triggers](docs/protocol.md#save_trigger) (after and before the write), [scheduled procedures](docs/protocol.md#save_schedule), [pipeline operators](docs/protocol.md#script-operators-simplejs-in-the-pipeline), and shared code via `import … from "procedures/<name>"`. See [docs/simplejs.md](docs/simplejs.md) |
| Clustering | fully-replicated nodes, no master, per-collection ownership, quorum writes, anti-entropy, cross-node transactions and script placement. See [docs/clustering.md](docs/clustering.md) |
| Operations | [`SERVER_INFO`](docs/protocol.md#server_info) and a Docker health check, [`GET_DATABASE_STATS`](docs/protocol.md#get_database_stats), [`REINDEX`](docs/protocol.md#reindex), [standardized error codes](docs/protocol.md#error-codes), [Prometheus metrics](#metrics) (opt-in), memory-bounded caching, graceful shutdown |

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

## Using the server

### Quickstart

[QUICKSTART.md](QUICKSTART.md) takes you from nothing to a running server, a first document saved and read back from
the command line, and a first query from one of the client libraries.

### Running the server

The server ships **without** an admin password: neither the bundled defaults nor the tracked `lwnrdb.cfg` set
`defaultAdminPassword`, so a first start with no password refuses to come up (see [Bootstrap](#bootstrap)). Put one
in the `lwnrdb.cfg` of the working directory before the first start:

```bash
mvn clean package -DskipTests
echo "defaultAdminPassword=<choose-a-strong-password>" >> lwnrdb.cfg
java -XX:+UseG1GC -XX:G1PeriodicGCInterval=60000 -Djdk.nio.maxCachedBufferSize=262144 -jar target/lwnrdb.jar
```

The jar's manifest enables final-field mutation (`Enable-Final-Field-Mutation: ALL-UNNAMED`, JEP 500): the EJson layer
writes the `final` fields of its request and response classes reflectively, and the JDK blocks that by default in a
future release. Launching from compiled classes instead of the jar needs `--enable-final-field-mutation=ALL-UNNAMED`.

With Docker, the image copies the credential-free `lwnrdb.cfg`, so a container started without a password stops at
startup with the `FATAL` message instead of coming up with a known password. Set `LWNRDB_ADMIN_PASSWORD` and the
entrypoint writes it into the container's `lwnrdb.cfg` as `defaultAdminPassword` before starting the server. There is
deliberately no build argument for it, because a password given to `docker build` ends up in the image layers and
shows in `docker history`. For anything beyond the password, mount your own `lwnrdb.cfg` over the image's; that is
also how the TLS and cluster secrets are supplied (the variable cannot be combined with a read-only mounted config,
put the password in that file instead):

```bash
docker build -t lwnrdb .
docker run -p 8989:8989 -v ./db:/app/db -v ./lwnrdb.cfg:/app/lwnrdb.cfg:ro lwnrdb
```

The image declares a `HEALTHCHECK` that runs `/app/healthcheck.sh` every 30 seconds (after a 60-second start period):
it reads `port` and `tlsEnabled` from `/app/lwnrdb.cfg` and runs the jar's own `--health` client, adding
`--tls --insecure` when TLS is on, so `docker ps` shows the container as `healthy` once the server answers `READY`.
It needs no password.

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

### Bootstrap

On first startup, if no admin user exists, the server creates a superadmin from `defaultAdminUsername` / `defaultAdminPassword`. At that moment the password is **required**: at least 8 characters, and not `administrator`. The server refuses to start (one `FATAL` log line, non-zero exit, no port opened):

- while `defaultAdminPassword` is the old well-known value `administrator` (in any letter case), **whether or not an admin already exists**. Edit `lwnrdb.cfg` and restart;
- while `defaultAdminPassword` is on a short list of the most commonly used passwords (`password123`, `changeme`, `12345678`, `qwertyuiop`, `admin1234`, …, compared ignoring case) or equals `defaultAdminUsername` ignoring case, also whether or not an admin already exists;
- while `defaultAdminPassword` is blank or missing **and** no admin user exists yet, since there would be no way to bootstrap one.

Once an admin exists, `defaultAdminUsername` / `defaultAdminPassword` are ignored and the password key may be removed from `lwnrdb.cfg`. The check reads only the configured value, never the stored hash, so a deployment whose admin was created with `administrator` and whose config no longer says so starts normally: **change that admin's password with [`SET_PASSWORD`](docs/protocol.md#set_password)**, because the server cannot tell.

### Configuration

Configuration is read from `lwnrdb.cfg` in the working directory, falling back to bundled defaults for any missing key. Lines starting with `#` and blank lines are ignored, so you can document special cases inline.

Every value is **validated at startup**. If any value is invalid, the server logs a `FATAL` error listing all problems and refuses to start. A whole-number key that the server holds as a 32-bit integer refuses anything past `2147483647`, and a size whose unit would overflow 64 bits (`9000000000tb`) is refused rather than wrapped.

**`lwnrdb.cfg` keys:**

| Key | Rule |
|---|---|
| `port` | Valid number, 1–65535 |
| `bindAddress` | Interface the client port binds to: an IP literal or a host name, checked at startup. Blank (the default) binds every interface; `127.0.0.1` accepts only local clients. If the port cannot be bound the server exits with status `1` |
| `metricsPort` | Valid number, 0–65535 (default `0`, off). Port of the [Prometheus metrics endpoint](#metrics). Must differ from `port` and, with clustering on, from `clusterPort`; if it cannot be bound the server exits with status `1` |
| `metricsBindAddress` | Interface the metrics port binds to, checked like `bindAddress` (default `127.0.0.1`). Blank binds every interface. Anything but a loopback address without a `metricsToken` is named in a `SECURITY WARNING` at startup |
| `metricsToken` | Bearer token a scrape must send as `Authorization: Bearer <token>` (default blank: no token asked) |
| `maxConnections` | Valid number ≥ 0. `0` means unlimited connections. Counts client sockets only. Like every protective limit where `0` turns the limit off (`maxUnauthenticatedConnectionsPerAddress`, `maxUnauthenticatedInvalidRequests`, `maxFailedAuthAttempts`, `authFailureDelayBaseMs`, `transactionMaxOps`, `transactionMaxAgeMs`, `maxListensPerConnection`, `maxListens`, `maxListensPerUser`, `maxFilterOperands`, `aggregateTimeoutMs`, `maxConcurrentScripts`, `maxConcurrentScriptsPerUser`, `maxConcurrentScriptsPerDatabase`), a `0` here is named in one `SECURITY WARNING` at startup |
| `maxFailedAuthAttempts` | Valid number ≥ 0 (default `5`). Failed `AUTHENTICATE`s one connection may make; the last one answers `401-4` and closes the connection. Never reset by a success. `0` disables the cap (the delay still applies) |
| `authFailureDelayBaseMs` | Valid number ≥ 0 (default `250`). Delay before the next `AUTHENTICATE` for a username from the same address after a failure, doubled per consecutive failure. `0` disables the delay |
| `authFailureDelayMaxMs` | Valid number ≥ 0 and ≥ `authFailureDelayBaseMs` (default `30000`). Cap on that delay |
| `maxConcurrentPasswordChecks` | Valid number ≥ 0 (default `0`, one per available processor). Password hashes allowed to run at once |
| `authVerifierCacheEnabled` | `true` or `false` (default `true`). Lets a repeated successful `AUTHENTICATE` skip the password hash: the server remembers a keyed MAC of the user's stored hash and the password (never the password), so a changed password stops matching at once, and a wrong password still pays the full hash and the failure delay |
| `maxRequestSize` | Human-readable size (default `64Mb`), at least `maxEntrySize` and at most `1Gb`. Largest request line an authenticated connection may send; a longer one is answered `400-22` and the connection closed. Before authenticating a line is capped at 64Kb whatever this says |
| `clientOutboundQueueMaxBytes` | Human-readable size (default `16Mb`). Largest unread backlog of responses and LISTEN updates one connection may hold, counted in the UTF-8 bytes they take on the wire (line terminators included, so a CJK character counts 3); past it the connection's listens end, `413-1` is queued and the connection is closed. A single larger response is still delivered when nothing waits behind it |
| `maxUnsentResponseBytes` | Human-readable size (default `0`, a quarter of the JVM's maximum heap). Largest total of encoded responses the server holds, across every connection, while they wait to be written or are being written; a read whose response would pass it is answered `503-17`. A response under 64Kb and the response to a write are always delivered, and LISTEN updates are bounded by `listenMaxRetainedBytes` instead |
| `clientWriteStallTimeoutMs` | Valid number ≥ 1000 (default `30000`). Milliseconds in which writing a response to a connection must move at least 64Kb before the server closes it (rolling back its open transaction), which frees what it held for a client that stopped reading |
| `authTimeoutMs` | Valid number ≥ 1 (default `10000`). Milliseconds a new connection has to authenticate, counted from accept and never extended; then `408-4` and the connection is closed |
| `maxListensPerUser` | Valid number ≥ 0 (default `256`). `LISTEN` registrations one user may hold across all of their connections; past it, `400-38`. `0` means unlimited and is warned at startup |
| `listenMaxRequestBytes` | Human-readable size (default `1Mb`). Largest `LISTEN` request line; a longer one is answered `400-37` before it runs |
| `listenMaxRetainedBytes` | Human-readable size (default `64Mb`). Total serialized length of the requests every `LISTEN` registration on the server keeps; past it, `503-15` |
| `listenRerunTimeoutMs` | Valid number ≥ 1 (default `10000`). Wall-clock budget of one background `LISTEN` re-run; past it the re-run is dropped with a warning and nothing is pushed |
| `listenThreads` | Valid number ≥ 1 (default `2`). Workers that re-run `LISTEN` queries after a write. Identical registrations (same user, same query) share one re-run; distinct queries re-run on these workers in parallel, each holding its own result while it runs |
| `transactionIdleTimeoutMs` | Valid number ≥ 1 (default `30000`). Milliseconds a connection with an open transaction may stay silent before the transaction is rolled back and its locks released; the next request answers `409-16` once |
| `snapshotMaxBytes` | Valid size > 0 (default `1Gb`). Bytes one [snapshot](docs/protocol.md#snapshots-start_snapshot-read_snapshot-release_snapshot) may keep: the old versions of the documents written since it was opened, plus a modelled cost per id it remembers. Past it the snapshot is aborted (`409-17`); the writes are never affected |
| `snapshotMaxOpen` | Valid number ≥ 1 (default `4`). Snapshots open on the node at once; one more answers `503-22` |
| `snapshotIdleTimeoutMs` | Valid number ≥ 1000 (default `300000`). Milliseconds a snapshot may go unread before the server releases it |
| `filePath` | Path that exists or can be created, and is writable by the process. Created owner-only (`0700`) when missing; see [File permissions](#file-permissions). The server stamps it with its storage format (`format.json`) and refuses to start on a folder written in another format; see [docs/compatibility.md](docs/compatibility.md) |
| `logPath` | Path that exists or can be created, and is writable by the process. Created owner-only (`0700`) when missing. Log cleanup deletes only expired files named `yyyy-MM-dd.log` here, so pointing it at a populated folder (even `.`) deletes nothing else |
| `backgroundProcessingThreads` | Valid number ≥ 1 |
| `backgroundQueueMaxEvents` | Valid number ≥ 1 (default `200000`). Bound on the background index/admin queue, counted in events (a bulk write is one event). A `SAVE`, `BULK_SAVE`, `DELETE` or `COMMIT_TRANSACTION` arriving while it is reached waits for room, holding no lock, then answers `503-16` with nothing written. Acknowledged writes are never dropped and replicated writes are never held back |
| `backgroundBacklogWaitMs` | Valid number ≥ 0 (default `5000`). Milliseconds such a write waits for room before `503-16`; `0` refuses at once |
| `maxLogFiles` | Valid number ≥ 1 |
| `maxPageSize` | Human-readable size (e.g. `2Mb`) > 0, and strictly greater than `maxEntrySize` |
| `maxEntrySize` | Human-readable size (e.g. `1Mb`) > 0, and strictly smaller than `maxPageSize` |
| `syncWrites` | `true` or `false` (default `true`). Sync every write's record in the collection's write-ahead log to the device before acknowledging it, so an acknowledged write survives a power loss (see [Durability](#durability)). `false` acknowledges once the OS has the write and logs a startup DURABILITY WARNING. With `true`, a platform that cannot sync a directory (Windows, for one) logs that warning and falls back to `false` instead of refusing to start |
| `walCheckpointBytes` | Size (default `8Mb`). Once a collection's write-ahead log passes it, a background worker syncs the pages and `pk.idx` its records touched together and rewrites the log as a single checkpoint, holding only the collection's read lock; a writer does it itself only past twice this size. Larger means fewer checkpoints and a longer replay after a crash |
| `walIdleCheckpointMs` | Long (default `60000`). A background worker checkpoints a collection's log once it has sat this long with records past its checkpoint, no open write group and no pending field-index work, so an idle collection is not replayed after a crash. A background checkpoint that fails (a full disk) is retried after 1 s, then 2, 4 … up to once a minute, and logs its stack trace only the first time. `0` turns it off |
| `schemaValidationBudget` | Valid number ≥ 1000 (default `5000000`). Evaluation steps one write request may spend against its collection's JSON Schema (one per schema node evaluated, one per regex matcher step), shared by every document of a `BULK_SAVE`; a request that exhausts it is refused with `400-23`. A large `BULK_SAVE` against an expensive schema may need it raised. The check runs before the collection lock and is reused under it when the schema has not changed; when it has, validation runs again under the collection write lock, so raising it raises the worst-case time one write holds that lock |
| `schemaMaxPatternLength` | Valid number > 0 (default `4096`). Longest `pattern` value or `patternProperties` key, in characters, that `SAVE_SCHEMA` accepts; a longer one is refused with `400-31`. A schema stored before the limit still loads. Keep it equal on every node of a cluster, since the node that runs `SAVE_SCHEMA` decides |
| `schemaMaxPatterns` | Valid number > 0 (default `256`). Most regular expressions (`pattern` values plus `patternProperties` keys, counted through `$ref`s) one schema may hold; `SAVE_SCHEMA` refuses more with `400-39` before compiling any. A stored or replicated schema with more still loads, with a warning. Keep it equal on every node of a cluster |
| `schemaPatternCacheMaxBytes` | Valid size > 0 (default `32Mb`). Estimated heap the node's compiled schema regular expressions may keep cached, weighed by their character sets, nodes and literal text; the least recently used are evicted past it. A pattern longer than `schemaMaxPatternLength` or heavier than the whole cache is compiled on every use and never cached |
| `defaultAdminUsername` | 3–64 characters from `A-Z a-z 0-9 _ -`, the same rule `CREATE_USER` applies (cluster peers refuse to replicate any other user name). The server refuses to start otherwise |
| `defaultAdminPassword` | Required (at least 8 characters, not `administrator`) when no admin user exists; may be blank once one does. `administrator`, a commonly used password and the admin username itself (all ignoring case) are always refused at startup, and a non-blank value shorter than 8 characters is a validation error |
| `maxMemory` | `auto` (default: 40% of the JVM's max heap) or a human-readable size, capped at 60% of the max heap with a startup warning; `0` (unlimited, named in the startup `SECURITY WARNING`) and `-1` (caching disabled) are also valid |
| `transientLoadPermits` | Valid number ≥ 1 (default `4`). Whole `pk.idx` or field-index loads that may not be cached, allowed to run at once: one whose upper-bound estimate from the file lengths (`pk.idx`, or an `.idx` plus its `.idxlog`) does not fit in `maxMemory`, every load while caching is disabled, and every load by a lock-free reader. Further ones wait; nothing is refused |
| `indexScanFallbackPercent` | Valid number, 1–100 (default `25`). Share of a collection of at least 4096 documents an index match may cover before a `FILTER` reads the collection instead of fetching the matches by id; a `GROUP_BY` and an unbounded `SORT` at the start of a pipeline also read the collection while it is not wholly cached. Index and scan answer identically, so this only changes speed; analyze still lists an index it consulted. `100` always uses the index |
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
| `scriptRoutingEnabled` | `true` or `false` (default `true`). Whether a script ([`RUN_SCRIPT`](docs/protocol.md#run_script), [`CALL_PROCEDURE`](docs/protocol.md#call_procedure)) may be placed on another live node instead of running where it arrived. Only a node that is alive **and** caught up on admin metadata is chosen. `scriptsEnabled` and the `script*` sandbox keys must be uniform across the cluster, and every node rolled, before enabling it — see [docs/clustering.md](docs/clustering.md) → *Scripts* |
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
| `scriptsEnabled` | `true` or `false` (default `true`). Whether clients may run scripts at all ([`RUN_SCRIPT`](docs/protocol.md#run_script)), whether stored procedures may be installed or called, and whether an `AGGREGATE` pipeline may carry a [script operator](docs/protocol.md#script-operators-simplejs-in-the-pipeline). On by default: a script is bounded by the server-fixed sandbox below and by permissions — only an admin, a database owner, or a user holding `scriptPermissions` for that database may start one, and every operation the script issues is authorized again on its own request. When `false` the operations are refused with `403-2` |
| `scriptInstructionBudget` | Valid number ≥ 1. Max interpreter instructions per script run; exceeding it aborts with `400-11` |
| `scriptTimeoutMs` | Valid number ≥ 1. Max wall-clock time per script run; exceeding it aborts with `408-1` |
| `scriptMaxDepth` | Valid number ≥ 1. Max nested call depth per script run; exceeding it aborts with `400-11` |
| `scriptMaxSourceBytes` | Human-readable size > 0. Max accepted script source; larger is rejected with `400-10` |
| `scriptMaxMemoryBytes` | Human-readable size > 0. Max bulk memory a script run may allocate; exceeding it aborts with `400-12`. Bounds the allocations that are proportional to a script-supplied length (a huge `repeat`/`padStart`, a dense array, a typed array, a `join`); ordinary small allocations are bounded by `scriptInstructionBudget` instead |
| `scriptMaxResultBytes` | Human-readable size > 0 (default `16Mb`). Max size of the value a script returns; a larger result fails the run with `400-15` (the `console` output still comes back). Use `db.cursor` to process more data than can be returned |
| `scriptMaxArrayLength` | Valid number ≥ 1 (default `16777216`). Longest array-like a script may expand in one native step (`apply` and `Reflect.apply`/`construct` argument lists, `Vector.from`, `Array.fromAsync`); a longer one fails the run with `400-30` before anything is allocated |
| `scriptCursorBatchSize` | Valid number ≥ 1 (default `500`). Default number of documents `db.cursor` fetches per batch |
| `scriptCursorMaxBatchSize` | Valid number ≥ 1 (default `5000`), and ≥ `scriptCursorBatchSize`. Upper clamp for a caller-supplied `batchSize`, so one call cannot materialise an unbounded batch |
| `aggregationScriptInstructionBudget` | Valid number ≥ 1 (default `1000000`). Max instructions **all** the [script operators](docs/protocol.md#script-operators-simplejs-in-the-pipeline) in one pipeline may execute before the query aborts with `400-11`. Per pipeline, not per document — one interpreter serves the whole query, so a runaway predicate aborts it rather than getting a fresh budget per row |
| `aggregationScriptTimeoutMs` | Valid number ≥ 1 (default `2000`). Wall clock for the same set of operators, also per pipeline; exceeding it aborts the query with `408-1` |
| `aggregationScriptMaxSourceBytes` | Human-readable size > 0 (default `16Kb`). Max source size of a single script operator; a larger one is rejected with `400-10` |
| `maxConcurrentScripts` | Valid number ≥ 0 (default `16`); `0` disables the cap. Max client-initiated script runs on this node at once; a caller refused after waiting `scriptQueueWaitMs` gets `503-6`. Triggers and schedules are bounded by their own pools instead, so the node-wide ceiling on concurrent interpreters is `maxConcurrentScripts + triggerThreads + scheduleThreads` — and that sum × `scriptMaxMemoryBytes` is the worst-case script heap, **additive** with `maxMemory` and the metadata cache budgets |
| `maxConcurrentScriptsPerUser` | Valid number ≥ 0 (default `4`); `0` disables the slice. Max script runs one user may have on this node at once — a trigger or schedule counts against its definer. Checked before the node-wide wait and never waits itself: a caller at its own ceiling gets `503-6` naming `user` at once. A trigger waits up to `scriptQueueWaitMs` for its definer's slices; one still refused is retried after `triggerRetryBackoffMs` without consuming an attempt; a scheduled run refused here skips that occurrence |
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
| `faultInjectionEnabled` | `true` or `false` (default `false`). **Testing and debugging only — never in production.** Lets an admin arm the [fault injection](docs/protocol.md#fault-injection-testing-and-debugging-only) points over the wire, which can pause or halt the node. While off, those operations answer `403-3` and every point costs one field read. A node started with it on logs a `SECURITY WARNING` |
| `powerLossShadowPath` | A folder path (default blank). **Power-loss harness only.** With `faultInjectionEnabled=true`, every force also records what it made durable into this folder: a copy of the forced file, or the forced folder's listing. `test_utils/test_power_loss.py` rebuilds the data folder from it after a `kill -9`, as a power loss would leave it. It must lie outside `filePath`; set without `faultInjectionEnabled=true`, or inside `filePath`, the server refuses to start. Every synced write gets slower by a copy of the file it forced |

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
authVerifierCacheEnabled=true
filePath=db
logPath=logs
maxPageSize=2Mb
syncWrites=true
walCheckpointBytes=8Mb
walIdleCheckpointMs=60000
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
# auto = 40% of the JVM's max heap; an explicit size above 60% of it is capped there
maxMemory=auto
# ms a transactional write waits for a busy collection lock before aborting (409-5)
transactionLockTimeoutMs=5000
shutdownTimeoutMs=15000
# largest request line once authenticated (64Kb before); longer lines get 400-22 and are closed
maxRequestSize=64Mb
# unread responses one connection may leave queued before it is closed (413-1)
clientOutboundQueueMaxBytes=16Mb
# encoded responses the server holds unsent across all connections (503-17); 0 = a quarter of the heap
maxUnsentResponseBytes=0
# ms a response write may make no progress before the connection is closed
clientWriteStallTimeoutMs=30000
# ms a new connection has to authenticate (408-4), and ms an open transaction may sit idle (409-16)
authTimeoutMs=10000
transactionIdleTimeoutMs=30000
# per-transaction ceilings (400-26) and maximum age however busy (408-5)
transactionMaxOps=100000
transactionMaxBytes=256Mb
transactionMaxAgeMs=600000
# the snapshots --dump reads a database from: bytes of old versions one may keep (409-17 past it),
# how many may be open (503-22 past it), and how long one may go unread before it is released
snapshotMaxBytes=1Gb
snapshotMaxOpen=4
snapshotIdleTimeoutMs=300000
# bytes every open transaction together may buffer (503-21); auto = a quarter of the heap
transactionTotalMaxBytes=auto
# LISTEN registrations per connection and per server (400-25), per user (400-38), request line (400-37),
# request bytes kept server-wide (503-15), the re-run wall clock and the re-run workers
maxListensPerConnection=64
maxListens=10000
maxListensPerUser=256
listenMaxRequestBytes=1Mb
listenMaxRetainedBytes=64Mb
listenRerunTimeoutMs=10000
listenThreads=2
# IN/NOT_IN operands and within-polygon vertices (400-27), #vector components, AGGREGATE wall clock (408-6)
# and the bytes one AGGREGATE may build (400-36)
maxFilterOperands=10000
maxVectorDimensions=16384
aggregateTimeoutMs=300000
aggregateMaxResultBytes=auto
# bytes every running AGGREGATE together may hold (503-20); auto = a quarter of the heap
aggregateTotalHeldBytes=auto
# share of a large collection an index match may cover before the query reads the collection instead
indexScanFallbackPercent=25
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
scriptRunHistoryMaxQueued=10000
scriptRunHistoryMaxRows=100000
scriptRunHistorySweepMs=3600000
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
# dead-lettered runs kept per database; 0 = no cap
triggerDeadLetterMaxRuns=10000
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

Two file-handling rules follow from the same concern: log cleanup (at startup and on the first log line
of each new day) only ever deletes files named `yyyy-MM-dd.log` that fell out of `maxLogFiles` (any
other file in `logPath` is left alone), and
`DROP_DATABASE` / `DROP_COLLECTION` never follow a symbolic link — a linked folder inside a database
is unlinked, and its target's contents are left untouched.

### Metrics

With `metricsPort` set, the server answers `GET /metrics` (and `HEAD`) on that port in the Prometheus text format
0.0.4. The endpoint is off by default and binds `127.0.0.1` unless `metricsBindAddress` says otherwise. It is plain
HTTP, separate from the client port and its TLS: keep it on loopback, or set `metricsToken` and put a TLS-terminating
proxy in front of it before exposing it. Any other path answers `404`, another method `405`, a missing or wrong token
`401`, a request head over 8 KB `431`; at most four scrapes are served at once (`503` past that), and a scraper must
send its request within 5 seconds.

```yaml
scrape_configs:
  - job_name: lwnrdb
    static_configs:
      - targets: ["db-host:9100"]
    authorization:
      credentials: "<metricsToken>"
```

In Docker, set `metricsBindAddress=0.0.0.0` and a `metricsToken` in `lwnrdb.cfg`, and publish the port
(`-p 9100:9100`).

Every metric is prefixed `lwnrdb_`. A scrape reads only counters the server keeps in memory and takes no collection
lock, so it answers while a collection is busy. Totals are reported across every user database, never per
collection, so the number of series stays bounded.

| Metric | Type | Labels | Meaning |
|---|---|---|---|
| `build_info` | gauge (always 1) | `version`, `protocol_version`, `storage_format_version` | What `SERVER_INFO` reports |
| `uptime_seconds` | gauge | | Seconds since the JVM started |
| `requests_total` | counter | `operation`, `status`, `code` | Requests answered. `code` is the error code, empty on success; `operation` is `UNKNOWN` for a line that could not be parsed, and `status` is `FORWARDED` for an answer relayed from a cluster peer |
| `request_duration_seconds` | histogram | `operation` | Seconds from the parsed request to its queued response; writing the response to the socket is not included |
| `connections` | gauge | | Open client connections |
| `connections_refused_total` | counter | `reason` (`max_connections`, `unauthenticated_per_address`) | Connections refused when accepted (`503-1`, `503-14`) |
| `in_flight_requests` | gauge | | Requests being served |
| `unsent_response_bytes`, `unsent_response_limit_bytes` | gauge | | Encoded responses waiting to be written, and the `maxUnsentResponseBytes` budget |
| `cache_bytes`, `cache_limit_bytes` | gauge | | Modelled heap bytes the user cache holds, and `maxMemory` (`+Inf` when unlimited, `0` with caching disabled) |
| `heap_used_bytes`, `heap_max_bytes` | gauge | | JVM heap |
| `gc_collections_total`, `gc_seconds_total` | counter | `gc` | Garbage collections and the time spent in them, per collector |
| `background_queued` | gauge | `queue` (`index`, `maintenance`, `history`) | Background events waiting |
| `triggers_total` | counter | `outcome` (`fired`, `failed`, `dropped`, `retried`, `dead_lettered`) | Trigger runs |
| `trigger_queue`, `schedule_queue` | gauge | | Trigger and schedule runs waiting to be dispatched |
| `schedules_total` | counter | `outcome` (`fired`, `failed`, `skipped`, `dropped`) | Scheduled procedure runs |
| `scripts_running`, `scripts_rejected_total` | gauge, counter | | Scripts running on the node, and scripts refused for lack of a slot |
| `in_doubt_transactions` | gauge | | Distributed transactions prepared on this node and not yet resolved |
| `fsync_total`, `fsync_failures_total` | counter | | Files and folders forced to the device, and the forces that failed |
| `fsync_duration_seconds` | histogram | | Seconds one force took |
| `databases`, `collections`, `documents`, `live_bytes` | gauge | | Totals across every user database, from the page rows `GET_DATABASE_STATS` reads |

The histograms share the bounds 0.5 ms, 1, 2.5, 5, 10, 25, 50, 100, 250 and 500 ms, 1, 2.5, 5 and 10 s.

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

`maxMemory` bounds the **user cache**: a background sweep (every 5s) sums the **modelled heap size** of the cached user collections and indexes and, while that total exceeds the budget, evicts the least-frequently-used of them. Each document and index entry is sized from the JVM's object layout — object headers (compact or not), reference width (4 bytes under G1, the collector the image and the run commands use, or 8 under ZGC, without compressed references, or with a heap of 32 GB and more), alignment, `String` backing arrays, map entries and hash tables — so the total tracks the heap the cache really holds rather than the documents' on-disk size, which is several times smaller (a small document costs about 3 KB of heap against under 200 bytes on disk). It is still the cache's own accounting, not measured JVM heap — nothing reads real heap to decide an eviction (`GET_DATABASE_STATS` reads it only to report). Values are human-readable (e.g. `512Mb`, `2Gb`), or `auto`, the default: 40% of the JVM's max heap (`-Xmx`, or the share of the machine or container the JVM picks by itself), so the cache follows the heap instead of a fixed number that a small box cannot hold. An explicit size above 60% of the max heap is capped at 60% and the startup log says so, because the collector, the page a scan is reading and in-flight requests need the rest. Two special values are accepted:
- `0` — unlimited; caching is on but eviction never triggers (suitable when `-Xmx` is already the only ceiling you want).
- `-1` — caching disabled; user collections and indexes are always read from disk. Admin collections are always cached regardless.

Eviction order is LFU. Access counts are recorded asynchronously and persisted in the `admin/collection_usage` collection; records older than 24h are pruned hourly. Within the cache, PK indexes are preferred over field indexes, which are preferred over full document maps.

A primary-key index or a field-index type that cannot fit in `maxMemory` (or any of them while caching is disabled) is never cached, and the log names it once. Keyed reads and writes then find their rows with one streaming pass over `pk.idx` for the ids they name, and index maintenance appends each change to that type's `.idxlog` without loading the type, folding the log into the `.idx` only once it outgrows it. Queries on such a field still load it from disk, so a deployment that queries it should raise `maxMemory` above the size the warning gives.

**Sizing `-Xmx` against the cap.** `maxMemory` bounds the cache's real heap, so the rest of `-Xmx` is what the collector, the page a scan is reading, in-flight requests and scripts get. With `maxMemory=auto` nothing needs sizing: the cache takes 40% of `-Xmx`. With an explicit value, size `-Xmx` to roughly twice `maxMemory`, plus the metadata cache and script budgets; startup logs a warning when `-Xmx > maxMemory × 2`, suggesting `maxMemory` around half of `-Xmx`. `maxMemory` cannot reclaim metaspace, JIT code or committed-but-unused heap, so the OS-visible process size stays above it. The image and the run commands use G1 (`-XX:+UseG1GC`): ZGC runs without compressed references, which cost 21% more live heap for the same load (183.1 MB against 151.8 MB under G1) and about 2.5x the peak process size on the Docker bench (1,161 MB against 468 MB at 0.3 scale). The flag is explicit because the JVM picks SerialGC by itself in a container under 2 CPUs or 1792 MB. Under ZGC the container's peak memory followed the heap ceiling, not the data: on the Docker bench (100k documents of about 512 B, about 190 MB of live heap after a full GC) the same run peaked at 1.2 GiB under a 1 GiB ceiling, 1.6 GiB under 1.4 GiB and 2.7 GiB under 2.8 GiB, with no extra CPU per operation and no allocation stalls under the smaller ceilings, so keep the ceiling near the rule above rather than at most of the container. If you run ZGC anyway, `-XX:SoftMaxHeapSize` keeps it near a smaller heap and lets it exceed it only under pressure (1.3 GiB peak under a 2.8 GiB ceiling). The Docker image runs the JVM with `-XX:MaxRAMPercentage=70`, so its heap is 70% of the container's memory limit (358 MB on a 512 MB container) and the default `maxMemory=auto` cache 40% of that; change it with `-e JDK_JAVA_OPTIONS=-XX:MaxRAMPercentage=50` (or `-Xmx` through the same variable). With an explicit `maxMemory`, keep the heap at least as large as `maxMemory` plus the metadata and script budgets, or the JVM can run out of memory before the cache budget evicts anything.

**The two other flags of the run commands.** `-Djdk.nio.maxCachedBufferSize=262144` stops the JDK from keeping a native copy buffer per thread for every large file write. Without it, a heap array written through a `FileChannel` leaves a temporary direct buffer of its full size cached on the carrier thread that wrote it, outside the heap, outside `maxMemory` and outside `MaxDirectMemorySize`. The server writes its write-ahead-log records and page slices in chunks of at most 128 KB, so it never needs a larger buffer, and the flag is a second line of defence. Without either measure, restoring a dump of about 4 MB lines into the image on a 512 MB container was killed for running out of memory three times out of three, holding 57-102 MB of such buffers. `-XX:G1PeriodicGCInterval=60000` lets an idle server give back heap it no longer uses. G1 resizes the heap only during a collection, so without it a server that goes quiet after a burst keeps its peak committed heap indefinitely (576 MB committed against 128 MB live after the bench; with periodic collections the process shrank by about 180 MB within six minutes, with 2-9 ms pauses).

**Running the jar on a glibc host.** The image is built on Alpine, whose musl allocator kept the server's native memory at about 115-125 MB. Under glibc the same server used about 185 MB more: glibc gives each thread its own malloc arena, the virtual-thread scheduler adds a platform carrier thread for each blocked file operation, and with transparent huge pages set to `always` each arena costs at least one 2 MB page. Glibc also keeps the compiler's freed memory. With the image's 70% heap rule a glibc JVM was killed on a 512 MB container. When you run `java -jar` on a glibc host, set `MALLOC_ARENA_MAX=2` in the environment, consider `-XX:TrimNativeHeapInterval=60000`, and keep the heap nearer the JVM's default quarter of memory than the image's 70%.

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

**Transactions.** A connection can open a transaction (`START_TRANSACTION`) to make several writes atomic (see [Transactions](docs/protocol.md#transactions)). Writes inside a transaction are buffered in the internal `admin/transactions` collection rather than applied, and become visible to the real collections only on `COMMIT_TRANSACTION`; `ROLLBACK_TRANSACTION` discards them. Locking is **lazy**: the first buffered write to a collection acquires that collection's exclusive write lock (waiting up to `transactionLockTimeoutMs`, then aborting the transaction with `409-5` so two concurrent transactions can never deadlock) and holds it — on the connection's own virtual thread — until commit/rollback. 
While held, other connections cannot read or write those collections, which is what keeps the committed batch atomic. The transaction's own reads apply its buffered mutations (**read-your-writes**); there is no snapshot isolation against other connections beyond that write-exclusivity. Committing replays the buffered operations through the normal write path, so field-index maintenance, page metadata and `LISTEN` notifications behave exactly as for ordinary writes. If the connection drops with a transaction open it is auto-rolled-back, and so is one left idle for `transactionIdleTimeoutMs` (the next request then answers `409-16` once). After a crash, a transaction that had not reached its commit point is discarded at startup, and one that had is finished.

### Consistency guarantees and known limitations

What a single node promises, and what it deliberately does not. The multi-node model is described in [docs/clustering.md](docs/clustering.md) → *What the cluster actually guarantees*.

**Guaranteed**

- **An acknowledged write survives a process crash.** Writes go to the files before the response is sent, so a `kill -9` or a JVM crash loses nothing that was acknowledged.
- **An acknowledged write survives a power loss** while `syncWrites` is on (the default). Every write is first appended to its collection's write-ahead log as the exact bytes it puts at each offset of each page and of `pk.idx`, and that record is synced to the device before the pages and `pk.idx` are touched; a created, renamed or deleted file has its folder synced too. Startup replays the log over whatever the files hold, so a power loss leaves every acknowledged write and no unacknowledged one. On a platform that cannot sync a directory the server logs a DURABILITY WARNING at startup and runs with `syncWrites=false`. See [Durability](#durability) for what it costs.
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

### Durability

With `syncWrites=true` a write is acknowledged once its record in the collection's write-ahead log
(`{collection}.wal`) is on the device: one sequential append and one force, whatever the write is. An insert, an
update, a delete, a relocation and a whole bulk write each log one record holding the exact bytes they put on each
page and in `pk.idx`, then write those files without forcing them. A checkpoint forces every page and `pk.idx` the log
touched since the last one, in parallel, then rewrites the log as a single checkpoint record; it runs in the
background, beside the collection's readers, once the log passes `walCheckpointBytes` (the writer runs it itself only
past twice that size), before a `pk.idx` rewrite, before a `REINDEX` with `"primaryKey": true`, once the log
has been idle for `walIdleCheckpointMs`, and at shutdown, where up to 32 logs are checkpointed in parallel within
`shutdownTimeoutMs` (the page-occupancy logs under `admin/pages` are left for the next startup's replay, which
restores them). A page vacuum does not checkpoint: it writes the rewritten page and its vacuum intent, forces them
together with the page it rewrites, logs a forced `REWRITE_BEGIN`, moves the rewritten page into place and syncs its
folder, then logs `REWRITE_END` with the page's new length together with the record of the rows it moved. Replay skips every older change to that page and cuts it back to that new length (or,
without the `REWRITE_END`, never below its length at `REWRITE_BEGIN`). After a power loss startup replays the log over whatever the files hold and cuts each file back to
its last logged end, so an acknowledged write is redone and an unacknowledged one leaves nothing. With
`syncWrites=false` there is no cut, since the bytes past a logged end may then be an acknowledged write whose record
did not reach the disk. A write whose
record is logged but whose files cannot be written is taken back and marked aborted in the log; when even that fails,
it is applied before the next write to the collection, or by the next startup. A write does not write the
collection's index-dirty marker either: its log record is what tells startup that its field-index work may be
unfinished, so startup re-queues that work for every id the replayed log wrote. A checkpoint that drops records whose
index work is still pending writes those ids into the new log, so startup re-queues them too, and shutdown leaves a
collection with pending work's log in place for the next startup to replay. Clearing an index marker is not forced, because a power loss that undoes it leaves a
journal behind that startup already settles. A field index's `.idxlog` appends are not forced either: the log record
of the write still covers that work, so a checkpoint forces the `.idxlog` files it touched together with the pages and
`pk.idx` before it drops records, and clearing an index marker forces them before the marker goes. The page-occupancy
rows of a user collection are kept in memory and written back within a second (and at shutdown); startup rebuilds them
from the page files anyway, so a crash costs nothing there.

Concurrent single-document `SAVE`s and `DELETE`s on one collection share one force (group commit): each writer queues
its write and waits for its turn; the one whose turn it is takes the collection lock, runs every queued write, appends
their records without forcing, forces the log once, and answers them all, so the others find their answer ready
without ever queueing on the lock. A write's page appends and `pk.idx` rows are written before that force, but the
blanks of the copies it supersedes wait for it, because a blank cannot be undone; startup cuts every page and `pk.idx`
back to their logged ends, so an append whose record never reached the disk leaves nothing. During the force the
writer downgrades the collection lock to a shared one: a `FIND_BY_ID` proceeds and may see a grouped write before its
force, while scans and other writers wait until the blanks landed. When the force fails, the writer takes the lock
back exclusively, takes every write of the group back and answers each with its error. Before hooks run for every
write of the group before anything is written, and the group's after-trigger runs are staged in one forced record
before the first write is appended and submitted only after the force; a write whose `_id` a hooked or triggered
write earlier in the group already names starts the next group. Writes inside a transaction, nested in a trigger, or
with `syncWrites=false` take the one-force-per-write path. With clustering on, the owner forces a group under the
exclusive lock, then replicates it as one `REPLICATE` (or one single-collection `REPLICATE_TX` when it mixes saves and
deletes) while point reads proceed and scans wait. A dirty read (`"dirtyRead":true`) can see a grouped write before
its force. A force costs what the disk makes it cost, and one collection's writes wait for it, so it bounds the write
rate of one collection: with 8 clients the Docker bench measured 4-5k synced inserts
and updates and 7-8k deletes per second. Measured for one force: about 0.25 ms on a Docker named volume (Linux, Docker
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

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.
The details (how to build, what the build checks, and how a release is cut) are in [CONTRIBUTING.md](CONTRIBUTING.md).
Security problems go through [SECURITY.md](SECURITY.md) instead of a public issue.

## License

MIT. Use it, change it, do whatever you want with it, just keep my name on it. See [LICENSE](LICENSE).
