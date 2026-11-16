# LightWeigh Non-Relational DataBase

LWNRDB is a non-relational database written completely in Java. Its main focus is to be lightweight, small, fast and easy to start.
Database speed (measured in IOPS) is not paramount.

## Motivation

I like learning new stuff and always had some complaints about things I would like to do in the most common database systems. At the same time, I wondered how do databases work internally. What's a better way to learn that building a new database engine?

As such, this DB is not intended to be the fastest one out there, the most reliable or even the simplest: it's a learning exercise that could be helpful for someone in some cases.

## Philosophy

- Simplicity is paramount: plain Java-next (always targeting the latest version and using new features) without any added libraries.
- Fast start-up times: currently less than a second
- Small size: a couple of megabytes
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
- Each collection is split in pages, one file per page. Inserts reuse the space freed by deletes before creating a new page.

## Status

LWNRDB does everything it set out to do, but it is still a learning exercise and not something to put production data
in. To try it, follow the [Quickstart](QUICKSTART.md).

Documents are stored as JSON lines in paged files and queried through an
[aggregation pipeline](docs/protocol.md#aggregate) (or [`FIND_BY_ID`](docs/protocol.md#find_by_id)) over a
[line-delimited JSON protocol](docs/protocol.md) on TCP, optionally with
[TLS](docs/operations.md#tls--secure-connections). On top of that it has:

- dates, times, [geo points and vectors](docs/protocol.md#custom-operators-type-specific) as value types
- [explain/analyze](docs/protocol.md#explain--analyze), [listenable queries](docs/protocol.md#listen) and
  [dirty reads](#concurrency--locking)
- [JSON Schema per collection](docs/protocol.md#schema-validation), [transactions](docs/protocol.md#transactions)
  and [users and permissions](docs/protocol.md#users--permissions)
- a built-in JavaScript engine for [scripts](docs/protocol.md#run_script),
  [stored procedures](docs/protocol.md#save_procedure), [triggers](docs/protocol.md#save_trigger),
  [schedules](docs/protocol.md#save_schedule) and [pipeline steps](docs/protocol.md#script-operators-simplejs-in-the-pipeline)
  (see [docs/simplejs.md](docs/simplejs.md))
- [clustering](docs/clustering.md) with fully replicated nodes and no master
- [Prometheus metrics](docs/operations.md#metrics), a health check and graceful shutdown

Things it will not do: compound indexes, sharding (every node holds all the data), compression, or competing on raw
IOPS.

## Performance

LWNRDB is built for indexed reads on data that mostly fits in memory, not for write throughput or bulk loading. These
numbers come from the project's own [benchmark](test_utils/bench/README.md), run on an Apple M5 under Docker Desktop on
2026-11-16. Every engine got 2 CPUs and 4 GB, the same 100,000 documents of about 512 bytes with `category` and `price`
indexed, 8 client processes, and synced writes.

| Operations per second | LWNRDB | PostgreSQL 18 | MySQL 8.4 | MongoDB 8.0 |
|---|---|---|---|---|
| Insert one document | 23,025 | **36,692** | 27,379 | 14,076 |
| Read one document by id | 93,263 | **95,974** | 63,517 | 38,936 |
| Replace one document by id | 15,925 | **33,851** | 13,099 | 13,419 |
| Delete one document by id | 24,076 | **40,041** | 17,533 | 18,522 |
| Equality on an indexed field, `LIMIT 50` | **25,697** | 24,174 | 17,065 | 24,399 |
| Range on an indexed field, sorted, `LIMIT 50` | 23,552 | **28,297** | 14,534 | 24,154 |
| Count of a range on an indexed field | **24,238** | 539 | 379 | 176 |
| Count with an unindexed filter (full scan) | 118 | 100 | **129** | 49 |
| Bulk load, documents per second | 105,000 | 220,400 | 134,400 | **413,200** |

In short:

- Indexed reads are its strong side. A count over an indexed range is answered from the index alone, and lookups by id
  are on par with PostgreSQL.
- Single-document writes run at about half of PostgreSQL's rate, with a higher p99. Without syncing every write it
  roughly doubles.
- Bulk loading is the weakest part.
- It uses the least disk (92 MB, against 574 MB for PostgreSQL) but more memory (1.4 GB peak, against 0.9 GB). Data
  bigger than the cache still works, just slower, so see [sizing](docs/operations.md#sizing).

It has not been measured with much bigger datasets, more clients, long runs or a cluster yet, so take the table as a
guide and not as a promise.

## Documentation

- [QUICKSTART.md](QUICKSTART.md): from nothing to a running server and a first query.
- [docs/protocol.md](docs/protocol.md): the wire protocol, every operation, permission and error code.
- [docs/operations.md](docs/operations.md): running a server, configuration, sizing, security, metrics, backups and
  recovery.
- [docs/simplejs.md](docs/simplejs.md): the JavaScript engine and everything you can script.
- [docs/clustering.md](docs/clustering.md): running several nodes.
- [docs/compatibility.md](docs/compatibility.md): what a new version may change.
- [clients/](clients/README.md): the client libraries.

## Client (shell, exec, dump/restore)

The server jar is also its own client. When the first argument starts with `--`, it connects to a server instead of
starting one:

```bash
java -jar lwnrdb.jar --shell [connection]
java -jar lwnrdb.jar --exec '<json>' [--exec '<json>' ...] [connection]
java -jar lwnrdb.jar --exec [connection] < commands.jsonl
java -jar lwnrdb.jar --dump [--databases a,b | --collections db.coll,...] [--compress] [connection] > backup.ldump
java -jar lwnrdb.jar --restore [connection] < backup.ldump
java -jar lwnrdb.jar --health [connection]
```

The connection flags are `--host` (default `127.0.0.1`), `--port` (default `8989`), `--user` (default `admin`),
`--password` (or the `LWNRDB_PASSWORD` environment variable), `--tls` and `--insecure` (TLS without checking the
certificate, only for the dev self-signed one).

- `--shell` reads one JSON request per line and pretty-prints the reply.
- `--exec` sends each request and prints each reply as is, stopping at the first one that is not `OK`.
- `--dump` writes a backup to standard output, gzipped with `--compress`.
- `--restore` reads a backup, plain or gzipped, from standard input.
- `--health` asks [`SERVER_INFO`](docs/protocol.md#server_info) without a password and exits `0` when the server is
  `READY`.

Exit codes are `0` for success, `1` when a request failed, `2` for a usage error and `3` when the server can't be
reached or refused the login.

A dump is just a list of ordinary requests (`CREATE_DATABASE`, `CREATE_COLLECTION`, `BULK_SAVE`, `SAVE_SCHEMA`,
`CREATE_INDEX`, procedures, triggers and schedules), so restoring is replaying it. Each database is read from one
snapshot while writes go on, so the dump is a consistent cut. Users, permissions and runtime state are not dumped.

Restore merges into whatever the target already has: documents with the same `_id` and definitions with the same name
are overwritten, and nothing else is deleted. The target's schema, before hooks and triggers apply to what is restored.
To replace instead of merge, drop the collection or database first. What a dump or restore can do depends on the
permissions of `--user`.

From Docker, override the command (add `-i` to feed a restore):

```bash
docker run --rm lwnrdb java -jar app.jar --dump --compress --host db.internal --password … > backup.ldump.gz
```

## Client libraries

There are official clients for Java (with Spring Data and Spring Boot), TypeScript, Python and .NET under
[`clients/`](clients/README.md). All of them are tested against the same protocol fixtures.

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

## Clustering

Several nodes can run as a cluster where every node holds all the data. Each collection is owned by one node, chosen
by hashing, so there is no master, and a client can connect to any node. Nodes find each other from a seed list and
talk over their own port, authenticated with a shared secret and optionally with mutual TLS.

```
clusterEnabled=true
clusterPort=9990
clusterAdvertisedAddress=10.0.0.11
clusterSeeds=10.0.0.10:9990
clusterSecret=change_this_to_a_shared_secret_of_32_plus_characters
```

With `clusterEnabled=false`, the default, a node is a plain standalone server. The details are in
[docs/clustering.md](docs/clustering.md).

## Query execution

Every query except `FIND_BY_ID` is a pipeline of steps. A `FILTER` uses the field indexes it can, combining several of
them when needed, and reads only the matching documents. Without a usable index it scans the collection one page at a
time instead of loading it whole. `SORT`, `GROUP_BY`, `JOIN` and `DISTINCT` can use an index too when they are the first
step, and a `COUNT` after indexed filters is answered without reading any document.

Indexes hold scalar values. Objects and arrays have their own hash indexes, which only help `FILTER` equality and `IN`.

## Concurrency & locking

Every collection and every field index has a read/write lock, and reads take it too. Many readers can run at the same
time, but a writer has the collection to itself. Each file has its own lock below that, so nothing is ever read
half-written.

A read can pass `"dirtyRead": true` to skip the collection lock. It won't wait for a long write, but it may see that
write half done.

A transaction locks each collection the first time it writes to it and keeps the lock until it commits or rolls back.
Other connections can't touch those collections meanwhile, and the transaction sees its own writes. If two transactions
wait on each other, one of them gives up after `transactionLockTimeoutMs`. An idle or disconnected transaction is rolled
back.

## Guarantees

What a single node promises (the cluster's are in [docs/clustering.md](docs/clustering.md)):

- An acknowledged write survives a crash. With `syncWrites` on, the default, it survives a power loss too: every write
  goes first to the collection's write-ahead log, which is synced before the answer, and startup replays it.
- A write or a transaction is either whole or absent after a crash.
- An index and a scan always give the same answer. Indexes are updated in the background, but a read takes the
  pending writes into account, and an index that can't answer makes the query scan instead.
- After-triggers are recorded before they run, so a crash doesn't lose them.

What it doesn't promise:

- With `syncWrites=false` a power loss can lose recent writes.
- Vector `nearest` is approximate unless you pass `"exact": true`.
- A schedule missed while the node was down is skipped, not run later.
- A dirty read can see a write half done.

## Q&A

- I want X feature. Can you add it for me?
  - If it is feasible and it makes sense, sure! But it might take some time. Please submit an issue
- Can I use it in production?
  - I wouldn't recommend doing that at least for now, as it is very experimental
- I discovered a bug. What should I do?
  - Please submit an issue with the steps to reproduce it and the expected result.

## Contributing

Pull requests are welcome! For major changes, please open an issue first to discuss what you would like to change.
How to build it and what the build checks are in [CONTRIBUTING.md](CONTRIBUTING.md). Security problems go through
[SECURITY.md](SECURITY.md) instead of a public issue.

## License

MIT. Use it, change it, do whatever you want with it, just keep my name on it. See [LICENSE](LICENSE).
