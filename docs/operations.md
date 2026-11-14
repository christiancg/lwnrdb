# LWNRDB operations guide

How to run an LWNRDB server for other people: what to set before the first start, how much memory and disk it needs,
how to secure, watch, back up and shut it down, what its log lines mean, and how to recover when something goes wrong.

- [Who this is for](#who-this-is-for)
- [Deployment checklist](#deployment-checklist)
- [Running the server](#running-the-server)
- [Bootstrap](#bootstrap)
- [Configuration](#configuration)
- [Sizing](#sizing)
- [Security hardening](#security-hardening)
- [Monitoring](#monitoring)
- [Logs](#logs)
- [Backups](#backups)
- [Shutdown and restart](#shutdown-and-restart)
- [Durability](#durability)
- [Upgrades](#upgrades)
- [Recovery runbook](#recovery-runbook)
- [Running a cluster](#running-a-cluster)

## Who this is for

This guide is for whoever runs the server: on a host with `java -jar`, or in a container from the project's image. To
try LWNRDB on your own machine first, follow the [Quickstart](../QUICKSTART.md). To write a client, read the
[wire protocol reference](protocol.md). A cluster has its own design document, [clustering.md](clustering.md), and the
rules for moving between releases are in [compatibility.md](compatibility.md).

LWNRDB is at 0.x and experimental (see the [README](../README.md#status)). Everything below describes what the server
does today. It does not promise that the server is ready for data you cannot afford to lose.

## Deployment checklist

| Item | What to set | Why |
|---|---|---|
| Runtime | JDK 27 or newer, or the project's Docker image | The build and the regex engine's Unicode data both require 27 ([Running the server](#running-the-server)) |
| Admin password | `defaultAdminPassword` for the first start, then a second admin user with [`CREATE_USER`](protocol.md#create_user-admin-only) | The server refuses to start without one, and nothing resets a lost admin password ([Bootstrap](#bootstrap), [Recovery](#a-lost-admin-password)) |
| Data folder | `filePath` on a local disk that honours `fsync`, with `syncWrites=true` | An acknowledged write survives a power loss only when the device keeps forced data ([Durability](#durability)) |
| File modes | `chmod 700` on `filePath` and `logPath`, `chmod 600` on `lwnrdb.cfg` and the keystore | They hold password hashes, data and secrets; the server warns but never changes an existing mode ([File permissions](#file-permissions)) |
| Network | `bindAddress` set to the interface clients use | Blank binds every interface |
| TLS | `tlsEnabled=true` with a CA-issued keystore in `tlsKeystorePath` and your own `tlsKeystorePassword` | Without it passwords and documents cross the network in plaintext ([TLS](#tls--secure-connections)) |
| Limits | Every protective limit above `0` | A `0` turns a limit off and is named in a startup `SECURITY WARNING` ([Security hardening](#security-hardening)) |
| Script fetch | `scriptFetchAllowlist` narrowed to the hosts scripts call, or `scriptFetchEnabled=false` | The shipped `*` lets any script make the server call any public host |
| Shutdown | `shutdownTimeoutMs` below the supervisor's kill grace period | Docker kills after 10 s by default and the shipped budget is 15 s ([Shutdown and restart](#shutdown-and-restart)) |
| Memory | Container memory limit or `-Xmx`, and `maxMemory=auto` unless you have measured | [Sizing](#sizing) |
| Monitoring | `metricsPort`, a `metricsToken` when it leaves loopback, and the alerts below | [Monitoring](#monitoring) |
| Backups | A scheduled `--dump`, a script that recreates users and grants, and a restore drill | A dump carries no users ([Backups](#backups)) |
| Logs | `logPath` on a disk with room for `(maxLogFiles + 1) x 2 x maxLogFileSize` | [Logs](#logs) |
| Health check | The image's `HEALTHCHECK`, or `--health` from your supervisor | [Health checks](#health-checks) |

## Running the server

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

## Bootstrap

On first startup, if no admin user exists, the server creates a superadmin from `defaultAdminUsername` / `defaultAdminPassword`. At that moment the password is **required**: at least 8 characters, and not `administrator`. The server refuses to start (one `FATAL` log line, non-zero exit, no port opened):

- while `defaultAdminPassword` is the old well-known value `administrator` (in any letter case), **whether or not an admin already exists**. Edit `lwnrdb.cfg` and restart;
- while `defaultAdminPassword` is on a short list of the most commonly used passwords (`password123`, `changeme`, `12345678`, `qwertyuiop`, `admin1234`, …, compared ignoring case) or equals `defaultAdminUsername` ignoring case, also whether or not an admin already exists;
- while `defaultAdminPassword` is blank or missing **and** no admin user exists yet, since there would be no way to bootstrap one.

Once an admin exists, `defaultAdminUsername` / `defaultAdminPassword` are ignored and the password key may be removed from `lwnrdb.cfg`. The check reads only the configured value, never the stored hash, so a deployment whose admin was created with `administrator` and whose config no longer says so starts normally: **change that admin's password with [`SET_PASSWORD`](protocol.md#set_password)**, because the server cannot tell.

## Configuration

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
| `snapshotMaxBytes` | Valid size > 0 (default `1Gb`). Bytes one [snapshot](protocol.md#snapshots-start_snapshot-read_snapshot-release_snapshot) may keep: the old versions of the documents written since it was opened, plus a modelled cost per id it remembers. Past it the snapshot is aborted (`409-17`); the writes are never affected |
| `snapshotMaxOpen` | Valid number ≥ 1 (default `4`). Snapshots open on the node at once; one more answers `503-22` |
| `snapshotIdleTimeoutMs` | Valid number ≥ 1000 (default `300000`). Milliseconds a snapshot may go unread before the server releases it |
| `filePath` | Path that exists or can be created, and is writable by the process. Created owner-only (`0700`) when missing; see [File permissions](#file-permissions). The server stamps it with its storage format (`format.json`) and refuses to start on a folder written in another format; see [docs/compatibility.md](compatibility.md) |
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
| `scriptRoutingEnabled` | `true` or `false` (default `true`). Whether a script ([`RUN_SCRIPT`](protocol.md#run_script), [`CALL_PROCEDURE`](protocol.md#call_procedure)) may be placed on another live node instead of running where it arrived. Only a node that is alive **and** caught up on admin metadata is chosen. `scriptsEnabled` and the `script*` sandbox keys must be uniform across the cluster, and every node rolled, before enabling it — see [docs/clustering.md](clustering.md) → *Scripts* |
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
| `scriptsEnabled` | `true` or `false` (default `true`). Whether clients may run scripts at all ([`RUN_SCRIPT`](protocol.md#run_script)), whether stored procedures may be installed or called, and whether an `AGGREGATE` pipeline may carry a [script operator](protocol.md#script-operators-simplejs-in-the-pipeline). On by default: a script is bounded by the server-fixed sandbox below and by permissions — only an admin, a database owner, or a user holding `scriptPermissions` for that database may start one, and every operation the script issues is authorized again on its own request. When `false` the operations are refused with `403-2` |
| `scriptInstructionBudget` | Valid number ≥ 1. Max interpreter instructions per script run; exceeding it aborts with `400-11` |
| `scriptTimeoutMs` | Valid number ≥ 1. Max wall-clock time per script run; exceeding it aborts with `408-1` |
| `scriptMaxDepth` | Valid number ≥ 1. Max nested call depth per script run; exceeding it aborts with `400-11` |
| `scriptMaxSourceBytes` | Human-readable size > 0. Max accepted script source; larger is rejected with `400-10` |
| `scriptMaxMemoryBytes` | Human-readable size > 0. Max bulk memory a script run may allocate; exceeding it aborts with `400-12`. Bounds the allocations that are proportional to a script-supplied length (a huge `repeat`/`padStart`, a dense array, a typed array, a `join`); ordinary small allocations are bounded by `scriptInstructionBudget` instead |
| `scriptMaxResultBytes` | Human-readable size > 0 (default `16Mb`). Max size of the value a script returns; a larger result fails the run with `400-15` (the `console` output still comes back). Use `db.cursor` to process more data than can be returned |
| `scriptMaxArrayLength` | Valid number ≥ 1 (default `16777216`). Longest array-like a script may expand in one native step (`apply` and `Reflect.apply`/`construct` argument lists, `Vector.from`, `Array.fromAsync`); a longer one fails the run with `400-30` before anything is allocated |
| `scriptCursorBatchSize` | Valid number ≥ 1 (default `500`). Default number of documents `db.cursor` fetches per batch |
| `scriptCursorMaxBatchSize` | Valid number ≥ 1 (default `5000`), and ≥ `scriptCursorBatchSize`. Upper clamp for a caller-supplied `batchSize`, so one call cannot materialise an unbounded batch |
| `aggregationScriptInstructionBudget` | Valid number ≥ 1 (default `1000000`). Max instructions **all** the [script operators](protocol.md#script-operators-simplejs-in-the-pipeline) in one pipeline may execute before the query aborts with `400-11`. Per pipeline, not per document — one interpreter serves the whole query, so a runaway predicate aborts it rather than getting a fresh budget per row |
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
| `triggerRunLogEnabled` | `true` or `false` (default `true`). Whether a fired trigger is recorded durably before it runs, so a run queued when the process dies is replayed at startup. Effects and the consumption of the record commit together, so a replay cannot double-apply; off trades that for one less admin write per fired trigger. The record is **node-local** — see [docs/clustering.md](clustering.md) → *Pending trigger runs are node-local* |
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
| `maxUnauthenticatedConnectionsPerAddress` | Valid number ≥ 0 (default `16`). Connections one remote address may hold before it authenticates; past it the server answers `503-14` and closes the socket, and authenticating frees the slot. Clients behind one NAT share it. `0` means unlimited and is warned at startup |
| `maxUnauthenticatedInvalidRequests` | Valid number ≥ 0 (default `5`). Requests a connection may send before it authenticates that are not a successful `AUTHENTICATE` (unparseable lines, `401-1` refusals, failed attempts); the last one closes the connection. `0` means unlimited and is warned at startup |
| `maxListens` | Valid number ≥ 0 (default `10000`). `LISTEN` registrations the whole server may hold at once; past it, `400-25`. `0` means unlimited and is warned at startup |
| `maxListensPerConnection` | Valid number ≥ 0 (default `64`). `LISTEN` registrations one connection may hold at once; past it, `400-25`. `0` means unlimited and is warned at startup |
| `maxFilterOperands` | Valid number ≥ 0 (default `10000`). Operands an `IN`/`NOT_IN` filter may list, and vertices a `within` polygon may have; past it, `400-27`. `0` means unlimited and is warned at startup |
| `deleteMaxMatches` | Valid number ≥ 1 (default `100000`). Documents one `DELETE` with a `condition` may delete; a condition matching more is refused with `400-44` before anything is deleted |
| `maxVectorDimensions` | Valid number ≥ 1 (default `16384`). Components a `#vector(...)` value may have; a longer literal is refused while the request is parsed |
| `aggregateTimeoutMs` | Valid number ≥ 0 (default `300000`). Wall-clock budget of one `AGGREGATE` and of a `LISTEN`'s first run; past it the pipeline stops and answers `408-6`. `0` means no limit and is warned at startup |
| `aggregateMaxResultBytes` | Human-readable size or `auto` (default, a quarter of the JVM's max heap). Bytes one `AGGREGATE` (and each `LISTEN` re-run) may build: the strings `MAP` adds, every `JOIN` row's joined array, the modelled heap of the rows `GROUP_BY`, an unbounded `SORT`, `JOIN` and `DISTINCT` hold, and every result row; past it, `400-36` |
| `aggregateTotalHeldBytes` | Human-readable size or `auto` (default, a quarter of the JVM's max heap). Bytes every running pipeline together may hold, counted like `aggregateMaxResultBytes`; a pipeline that would pass it stops and answers `503-20`, which is safe to retry |
| `transactionMaxOps` | Valid number ≥ 0 (default `100000`). Operations one transaction may buffer; past it a write answers `400-26` and the transaction stays usable for `ROLLBACK_TRANSACTION`. `0` means unlimited and is warned at startup |
| `transactionMaxBytes` | Human-readable size (default `256Mb`). Bytes of documents one transaction may buffer; past it, `400-26` |
| `transactionTotalMaxBytes` | Human-readable size or `auto` (default, a quarter of the JVM's max heap). Bytes of documents every open transaction together may buffer; a write that would pass it answers `503-21`, buffers nothing and leaves its transaction usable, so retrying it is safe |
| `transactionMaxAgeMs` | Valid number ≥ 0 (default `600000`). Maximum age of a transaction however active it is; an older one is rolled back and the next request answers `408-5`. `0` means no limit and is warned at startup |
| `logLevel` | `FATAL`, `ERROR`, `WARNING` or `INFO` (default `INFO`, which writes everything). Lowest severity a log entry must have to be written (see [Logs](#logs)) |
| `maxLogFileSize` | Human-readable size > 0 (default `100Mb`). Size at which the day's log file is renamed `yyyy-MM-dd.1.log` (replacing an earlier one) and a fresh file starts, so one day never holds more than twice this |
| `scheduleMaxTimeoutMs` | Valid number ≥ 1 (default `300000`). Largest `timeoutMs` a `SAVE_SCHEDULE` may ask for; a larger one is refused with `400-29` |
| `scriptHeapCeilingPercent` | Valid number between 10 and 95 (default `75`). Share of the JVM's max heap the live set, as measured after the last garbage collection, may reach before a script allocating more is stopped with a memory error; a backstop for what `scriptMaxMemoryBytes` cannot see |
| `scriptFetchEnabled` | `true` or `false` (default `true`). Whether scripts may make outbound HTTP requests with `fetch`, from `RUN_SCRIPT`, `CALL_PROCEDURE`, triggers and scheduled procedures; never from a pipeline script or a before hook. With `true` the server refuses to start unless it runs from its packaged jar, which installs the resolver that pins each fetch to the address it checked (see [docs/simplejs.md](simplejs.md)) |
| `scriptFetchAllowlist` | Comma-separated hosts (default `*`). Hosts a script may reach: an exact host, a `*.example.com` wildcard (sub-domains, not the apex), or a bare `*` for every public host; an empty list denies everything. Under a wildcard a host resolving to a loopback, private, link-local, CGNAT, multicast, reserved or unique-local address is refused; only an exact entry reaches such an address, and an exact `localhost` or private literal is named in a `SECURITY WARNING`. `*` is warned about at every startup. Redirects are never followed |
| `scriptFetchMaxConcurrent` | Valid number ≥ 1 (default `8`). Fetches one script run may have in flight at once; one more fails with the catchable `TypeError` `fetch: too many concurrent requests` |
| `scriptFetchMaxResponseBytes` | Human-readable size > 0 (default `1Mb`). Largest response body; a larger one fails the call as soon as the cap is passed, without buffering past it |
| `scriptFetchTimeoutMs` | Valid number > 0 (default `5000`). Wall-clock budget of one fetch, after which it fails with a catchable `TypeError`; any other network failure fails with `fetch failed` and its cause goes to the server log only |
| `scriptRunHistoryEnabled` | `true` or `false` (default `true`). Records each finished script run as a document in the reserved `script_runs` collection of the database it ran against, created on the first row. Writes are asynchronous and best effort: a failed one is logged and dropped |
| `scriptRunHistoryKinds` | Comma-separated, from `CALL_PROCEDURE`, `TRIGGER`, `SCHEDULE`, `RUN_SCRIPT` and `BEFORE_HOOK` (default `CALL_PROCEDURE,TRIGGER,SCHEDULE`). Run kinds recorded; `BEFORE_HOOK` records failures only, and an empty list records nothing |
| `scriptRunHistoryIncludeLogs` | `true` or `false` (default `false`). Whether a run's captured console output goes into its history row |
| `scriptRunHistoryMaxErrorChars` | Valid number > 0 (default `2000`). Characters of a failed run's error message kept on its row |
| `scriptRunHistoryMaxQueued` | Valid number > 0 (default `10000`). History rows waiting to be written; a run finishing while the queue is full is not recorded (counted in `historyDropped`, one warning per episode) |
| `scriptRunHistoryMaxRows` | Valid number ≥ 0 (default `100000`). Rows kept in each database's `script_runs`; the sweep deletes the oldest beyond it. `0` means no cap, so only the retention applies |
| `scriptRunHistoryRetentionMs` | Valid number > 0 (default `604800000`, 7 days). How long a history row is kept before the sweep deletes it; the sweep runs only on the node that owns the collection |
| `scriptRunHistorySweepMs` | Valid number ≥ 1000 (default `3600000`). How often the history sweep runs |
| `triggerMaxAttempts` | Valid number ≥ 1 (default `3`). Attempts a failed after-trigger gets in total, the first delivery included, before it is dead-lettered: kept with `status=DEAD` and its last error for [`LIST_TRIGGER_RUNS`](protocol.md#list_trigger_runs-admin-only) and [`RESOLVE_TRIGGER_RUN`](protocol.md#resolve_trigger_run-admin-only). A missing definer or procedure, an exceeded cascade depth, a cancellation and a queue-overflow drop are never retried. `1` dead-letters on the first failure |
| `triggerRetryBackoffMs` | Valid number ≥ 0 (default `1000`). Pause before a failed trigger's second attempt, doubled for each attempt after it |
| `triggerRetryMaxBackoffMs` | Valid number ≥ 0 (default `60000`). Ceiling of that doubling pause |
| `triggerDeadLetterRetentionMs` | Valid number > 0 (default `604800000`, 7 days). How long a dead-lettered run is kept, counted from when it was dead-lettered, before the sweep drops it |
| `triggerDeadLetterMaxRuns` | Valid number ≥ 0 (default `10000`). Dead-lettered runs kept per database; one more deletes the oldest beyond it (logged once per episode). `0` means no cap, so only the retention applies |
| `faultInjectionEnabled` | `true` or `false` (default `false`). **Testing and debugging only — never in production.** Lets an admin arm the [fault injection](protocol.md#fault-injection-testing-and-debugging-only) points over the wire, which can pause or halt the node. While off, those operations answer `403-3` and every point costs one field read. A node started with it on logs a `SECURITY WARNING` |
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

## Sizing

The JVM heap holds four budgets, and the process needs more than the heap:

- the user cache, `maxMemory` (`auto` takes 40% of the heap);
- the metadata caches, `metadataCacheMaxBytes` (72 MB by default) and `metadataCacheMaxEntries`;
- scripts, up to `maxConcurrentScripts x scriptMaxMemoryBytes` (16 x 64 MB by default) when every slot runs a script
  that uses its whole budget;
- everything else: the collector's headroom, the page a scan is reading, requests and responses in flight, buffered
  transactions (`transactionTotalMaxBytes`) and the rows running aggregates hold (`aggregateTotalHeldBytes`), the last
  two capped at a quarter of the heap each by default.

The image gives the JVM 70% of the container's memory limit (`-XX:MaxRAMPercentage=70`), so on a container the only
number to choose is that limit. The startup log warns when the budgets cannot fit the heap.

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

**Two cache budgets, not one.** `maxMemory` bounds the *user* document/index cache
only. The admin metadata caches — stored procedure sources, per-collection JSON
Schemas, trigger lists and schedule definitions — are bounded separately by
`metadataCacheMaxBytes` (the byte-budgeted ones) and `metadataCacheMaxEntries` (the
count-budgeted ones), and sit on top of it. All of them
are LRU-evicted and backed by disk, so lowering them only costs a re-read. Size
`-Xmx` against the sum: the server logs a warning at startup when the budgets total
more than the heap. `GET_DATABASE_STATS` reports the live footprint under
`memory.adminMetadataCache`.

### Disk

Everything the server writes lives under `filePath`, plus the logs under `logPath`. Beyond the documents themselves, a
collection takes:

| What | Up to |
|---|---|
| Dead bytes in its pages | An update or delete leaves its old copy as blank space; a page is vacuumed once a quarter of it is dead, so pages hold up to about a third more than their live bytes |
| Its primary-key index, `pk.idx` | One short row per write since the last compaction |
| Its write-ahead log, `{collection}.wal` | `walCheckpointBytes` (8 MB) before a background checkpoint, twice that before the writer checkpoints itself |
| Each field index | The `.idx` file, plus an `.idxlog` of changes that is folded back once it reaches the size of the `.idx` (at least 64 KB) |

And the server as a whole takes:

| What | Up to |
|---|---|
| Snapshot images while a dump runs | `snapshotMaxBytes` (1 GB) per open snapshot, `snapshotMaxOpen` (4) snapshots; deleted when the snapshot is released and at startup |
| Script run history | `scriptRunHistoryMaxRows` (100,000) rows per database, kept `scriptRunHistoryRetentionMs` (7 days) |
| Dead-lettered trigger runs | `triggerDeadLetterMaxRuns` (10,000) per database |
| Cluster tombstones | One line per deleted document per collection, kept `tombstoneRetentionMs` (1 day) |
| Logs | `(maxLogFiles + 1) x 2 x maxLogFileSize`: 1.6 GB with the shipped 7 days and 100 MB |

`lwnrdb_live_bytes` and the per-collection numbers in [`GET_DATABASE_STATS`](protocol.md#get_database_stats) give the
live document bytes. Keep free space of at least the larger of the biggest collection's files and twice
`walCheckpointBytes` for every collection written at once, plus `snapshotMaxBytes` while dumps run. The server keeps
running on a full disk and recovers once space returns ([Disk full](#disk-full)), but every write fails until then.

## Security hardening

Two settings make the server refuse to start rather than run insecurely: a weak or blank `defaultAdminPassword` (see
[Bootstrap](#bootstrap)), and `scriptFetchEnabled=true` when the server does not run from its packaged jar, where the
resolver that pins a fetch to the address it checked is not installed. Everything else is a warning at every startup, so a clean startup log is the goal.

| Startup line | Remove it by |
|---|---|
| `SECURITY WARNING: tlsEnabled=false, so client connections are plaintext` | `tlsEnabled=true` with a CA-issued keystore ([TLS](#tls--secure-connections)) |
| `SECURITY WARNING: tlsKeystorePassword is still the shipped value` | Your own `tlsKeystorePassword` |
| `SECURITY WARNING: these limits are set to 0, which turns them off` | A positive value for each key it names, unless something in front of the server enforces the same limit |
| `SECURITY WARNING: the data folder (filePath) at ... is accessible by group or other users` (also the log folder, the keystore and `lwnrdb.cfg`) | The `chmod` the line prints ([File permissions](#file-permissions)) |
| `SECURITY WARNING: the metrics endpoint on port ... without a metricsToken` | `metricsToken`, or `metricsBindAddress=127.0.0.1` |
| `SECURITY WARNING: clusterTlsEnabled=false` | `clusterTlsEnabled=true` with one keystore shared by every node |
| `SECURITY WARNING: scriptFetchAllowlist names ... exactly` | Removing the internal host from `scriptFetchAllowlist` unless scripts must reach it |
| `scriptFetchAllowlist is '*'` | Listing the hosts scripts call, or `scriptFetchEnabled=false` |
| `SECURITY WARNING: faultInjectionEnabled=true` | `faultInjectionEnabled=false`; it is for tests only |
| `DURABILITY WARNING: syncWrites=false` | `syncWrites=true` ([Durability](#durability)) |
| `DURABILITY WARNING: syncWrites=true, but this platform cannot sync a directory` | Running on a platform that can; the server cannot make a created or renamed file durable there |

Beyond the startup log:

- Give each application its own user with the narrowest grant that works ([permission model](protocol.md#permission-model))
  rather than sharing the admin.
- Keep `triggersEnabled` off unless you use triggers, and `scriptsEnabled` off if no one runs scripts.
- Keep the admin password out of `lwnrdb.cfg` once the first admin exists; the server ignores it from then on.

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

## Monitoring

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

### Health checks

- **The image** runs `/app/healthcheck.sh` every 30 seconds after a 60-second start period: the jar's `--health`
  client against the configured port, so `docker ps` shows `healthy` once the server answers `READY`.
- **Anywhere else**, run `java -jar lwnrdb.jar --health --host <host> --port <port>` (add `--tls`, and `--insecure`
  for a self-signed certificate). It needs no password and exits `0` only when the state is `READY`.
- **A load balancer** can send [`SERVER_INFO`](protocol.md#server_info) itself, unauthenticated: `READY` serves
  requests, `SYNCING` is a cluster node finishing its first admin sync, `SHUTTING_DOWN` answers every request with
  `503-13`. Take a node out of rotation on anything but `READY`.

### Suggested alerts

Starting points, in PromQL over the metrics above. Tune the windows to your traffic.

| Condition | Means | First action |
|---|---|---|
| `up{job="lwnrdb"} == 0` | The endpoint does not answer | Check the process and the [startup log](#logs) |
| `increase(lwnrdb_fsync_failures_total[5m]) > 0` | The device refused to persist a write | Check the device and its free space ([A failed fsync](#a-failed-fsync)) |
| `histogram_quantile(0.99, sum by (le) (rate(lwnrdb_fsync_duration_seconds_bucket[5m]))) > 0.05` | Forces take over 50 ms; every write to a collection waits for them | Check the disk's latency and what else writes to it |
| `lwnrdb_in_doubt_transactions > 0` for 10 minutes | A distributed transaction lost its coordinator | [In-doubt distributed transactions](#in-doubt-distributed-transactions) |
| `increase(lwnrdb_triggers_total{outcome="dead_lettered"}[1h]) > 0` | A trigger kept failing and was set aside | [Dead-lettered trigger runs](#dead-lettered-trigger-runs) |
| `increase(lwnrdb_triggers_total{outcome="dropped"}[1h]) > 0` or the same for `lwnrdb_schedules_total` | A full queue dropped a run | Raise the queue size or slow the writes that fire it |
| `deriv(lwnrdb_background_queued{queue="index"}[15m]) > 0` for 15 minutes | Index maintenance falls behind the writes | Writes will start answering `503-16`; check CPU and disk |
| `lwnrdb_heap_used_bytes / lwnrdb_heap_max_bytes > 0.9` for 15 minutes | The heap is nearly full after collections | [Sizing](#sizing) |
| `lwnrdb_unsent_response_bytes / lwnrdb_unsent_response_limit_bytes > 0.8` | Clients read their responses slower than they ask | Reads will start answering `503-17`; find the slow client |
| `rate(lwnrdb_connections_refused_total[5m]) > 0` | Connections hit `maxConnections` or the per-address cap | Raise the cap or find the client that leaks connections |
| `sum by (code) (rate(lwnrdb_requests_total{code=~"5.."}[5m])) > 0` | Server-side errors, by code | Look the code up in the [error table](protocol.md#error-codes) |
| `rate(lwnrdb_scripts_rejected_total[5m]) > 0` | Every script slot was busy | Raise `maxConcurrentScripts` or find the long script in `script_runs` |

## Logs

The server writes every entry to `logPath` (default `logs`, created `0700`) and to standard output, so
`docker logs` shows it too.

- **Files.** One file per day, `yyyy-MM-dd.log`. Once it reaches `maxLogFileSize` it is renamed `yyyy-MM-dd.1.log`
  (replacing an earlier one) and a new file starts, so a day holds at most twice that size. At startup and on the first
  entry of each new day, dated files older than `maxLogFiles` days are deleted; nothing else in `logPath` is touched.
- **Lines.** `HH:MM:SS.nnnnnnnnn-SEVERITY-class - message`, for example
  `14:02:11.348519-INFO-org.techhouse.ServerMain - LWNRDB 0.1.0 starting`. An exception adds
  ` -> ` and its message, then its stack trace on the following lines. Line breaks and other control characters
  inside a message are escaped, so a message is always one line and `grep` finds whole entries.
- **Level.** `logLevel` sets the lowest severity written: `FATAL`, `ERROR`, `WARNING` or `INFO` (the default, which
  writes everything).

The lines below need someone to act. Search for the text before the first `...`.

### The server refused to start

Every refusal is one `FATAL` line and exit status `1`, and no client is ever served.

| Line starts with | Cause and action |
|---|---|
| `Configuration validation failed, the application will not start:` | Every invalid key follows on the same line, separated by a literal `\n`. Fix `lwnrdb.cfg` ([Configuration](#configuration)) |
| `Insecure configuration, the application will not start:` | A weak, well-known or missing `defaultAdminPassword` ([Bootstrap](#bootstrap)), or script fetch without address pinning; the line says which |
| `The server will not start: .../format.json says this folder holds storage format` | The data folder belongs to another release. The line says which release to run, or how to dump and restore ([compatibility.md](compatibility.md#the-format-stamp)) |
| `The server will not start:` followed by anything else | An I/O failure while preparing `filePath` or `logPath`, such as a missing permission. The message names the path |
| `I/O error while starting server on port` | The client port is taken or not bindable on `bindAddress` |
| `Failed to start the cluster server` | The cluster listener could not start; the exception that follows names the cause |

### Startup warnings that need action

| Line starts with | Action |
|---|---|
| `The following configs are missing and will be using defaults:` | Nothing: `lwnrdb.cfg` holds only your overrides, and the listed keys take the defaults in the [table](#configuration) |
| `SECURITY WARNING:` / `DURABILITY WARNING:` | See [Security hardening](#security-hardening) |
| `These collections stopped with field-index work outstanding` | `REINDEX` each collection it names ([Stale indexes](#a-stale-or-suspect-index)) |
| `These field indexes were left half-built or half-dropped` | `REINDEX` (or `DROP_INDEX`) each field it names; until then the field is answered by a scan |
| `The configured memory budgets total ... but JVM -Xmx is only` | Lower the budgets or raise the heap ([Sizing](#sizing)) |
| `maxMemory=... is more than 60% of the JVM heap` | The cache was capped; raise the heap, lower `maxMemory`, or use `auto` |
| `JVM -Xmx (...) is more than 2x the configured maxMemory` | Raise `maxMemory` towards half the heap, or use `auto` |
| `The primary-key index of ... needs at least` | The collection's keys alone outgrow `maxMemory`, so keyed reads and writes scan `pk.idx`; raise `maxMemory` |
| `These names share one on-disk path and collide on a case-insensitive filesystem` | Two names differ only in case on a filesystem that ignores case; move the data to a case-sensitive one |
| `Database '...' is stored in the folder this node keeps its cluster state in` | Copy that database's data elsewhere before enabling clustering |

### Errors while running

| Line starts with | Action |
|---|---|
| `Failed to seed the write clock from disk` and the other `Could not seed the write clock` lines | Versions may go backwards after this restart, which matters in a cluster. Read the exception, fix the file it names, restart |
| `Failed to recover in-doubt transactions at startup` | [In-doubt distributed transactions](#in-doubt-distributed-transactions) |
| `Failed to load the schedule definitions` | Some schedules did not load; the exception names the file |
| `Could not checkpoint ... in the background` and `Still cannot checkpoint` | Usually a full disk. The server keeps writing and retries with a backoff; free space ([Disk full](#disk-full)) |
| `Trigger queue full; dropped` / `Schedule queue full; dropped` | A run was dropped; see the alerts above |
| `The shutdown budget ran out with ... write-ahead log(s) not checkpointed` | Nothing is lost: the next startup replays them. Raise `shutdownTimeoutMs` if it happens at every stop |
| `... request(s) still running at the end of the shutdown budget` / `Shutdown step '...' failed` | Check the next startup for the index warnings above |

After a crash, `Repaired the field indexes of db|coll for N document(s) in X ms` is the healthy line: startup re-did
the index work the crash interrupted.

## Backups

A dump is the backup. [`--dump`](../README.md#client-shell-exec-dumprestore) reads each database through one
snapshot while writes go on, and writes ordinary requests that `--restore` replays. The README's client section
describes the flags, the format and which user may dump what.

### A scheduled dump

Run it as the admin, so every database is included, from a host or container that reaches the server. Keep the
password in the environment (`LWNRDB_PASSWORD`), not on the command line:

```sh
#!/bin/sh
set -eu
out=/backups/lwnrdb-$(date +%F).ldump.gz
java -jar /opt/lwnrdb/lwnrdb.jar --dump --compress --host db.internal --tls > "$out.partial"
mv "$out.partial" "$out"
find /backups -name 'lwnrdb-*.ldump.gz' -mtime +14 -delete
```

The client exits non-zero when the dump stops (`1`) or cannot reach or authenticate to the server (`3`), so `set -e`
leaves only the `.partial` file behind and the job fails visibly. A dump fails rather than mixing two cuts when its
snapshot passes `snapshotMaxBytes` (`409-17`), when `snapshotMaxOpen` snapshots are already open (`503-22`), or when a
collection it reads is dropped. Run it again.

### What a dump does not carry

Users, their grants and database owners never leave the server: password hashes are not exported, and
[`CREATE_USER`](protocol.md#create_user-admin-only) takes only a plaintext password. Keep a script that recreates
them with `CREATE_USER`, [`CHANGE_PERMISSIONS`](protocol.md#change_permissions-admin-only) and
[`SET_DATABASE_OWNERS`](protocol.md#set_database_owners-admin-only), and run it against the restored server. Restored
procedures, triggers and schedules belong to the user who ran the restore, and run with that user's permissions.
[`LIST_USERS`](protocol.md#list_users-admin-only) shows what that script must recreate.

Script run history, trigger runs and in-doubt transactions are runtime state and are not dumped either.

### A restore drill

A backup is only as good as its last restore. Restore into a scratch server on another port and folder, then compare:

```sh
mkdir -p /tmp/drill && cd /tmp/drill
printf 'port=9899\ndefaultAdminPassword=%s\n' "$DRILL_PASSWORD" > lwnrdb.cfg
java -XX:+UseG1GC -jar /opt/lwnrdb/lwnrdb.jar &
until java -jar /opt/lwnrdb/lwnrdb.jar --health --port 9899 > /dev/null 2>&1; do sleep 1; done
LWNRDB_PASSWORD="$DRILL_PASSWORD" java -jar /opt/lwnrdb/lwnrdb.jar --restore --port 9899 < /backups/lwnrdb-2026-11-12.ldump.gz
LWNRDB_PASSWORD="$DRILL_PASSWORD" java -jar /opt/lwnrdb/lwnrdb.jar --exec '{"type":"GET_DATABASE_STATS"}' --port 9899
```

Compare each collection's `entryCount` with the production server's at the time of the dump, then stop the
scratch server and delete `/tmp/drill`. Restore merges into what a server already holds; to replace a collection,
drop it first ([README](../README.md#client-shell-exec-dumprestore)).

### Copying the data folder

A copy of `filePath` is a valid backup only when the server is stopped, and only as a whole: the write-ahead logs
(`{collection}.wal`), the format stamp `format.json` and every file under `admin/` belong to it. A copy of a running
server's folder is not supported, since it can catch a page and its log at different moments. Restore such a copy
only into the same release (the stamp refuses any other).

## Shutdown and restart

`SIGTERM` (what `docker stop` sends) or `Ctrl+C` starts the shutdown. The log shows
`Shutting down; up to 15000ms to finish outstanding work` and, at the end, `Shutdown complete`.

**Graceful shutdown.** On SIGTERM the server runs an ordered shutdown within
`shutdownTimeoutMs`: it stops accepting new connections, stops the background sweeps,
rolls back open transactions (releasing their collection locks, but leaving PREPARED
2PC slices for recovery), drains the trigger queue and then the background index queue,
stops the listen workers, and finally leaves the cluster. Work still outstanding when
the budget expires is abandoned with a warning naming what was dropped — an abandoned
index event means the affected collection's field indexes may be stale, so run `REINDEX`
on it. Set `shutdownTimeoutMs` below whatever SIGKILL grace period supervises the
process (Docker's default is 10s, so lower it or raise `--stop-timeout`).

After the in-flight requests and the queues, the shutdown also releases open snapshots, writes the page rows back and
checkpoints the write-ahead logs. A `SIGKILL` skips all of it and is still safe: the next startup replays every log
([Durability](#durability)), at the cost of a longer start.

## Durability

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

## Upgrades

The rules live in [compatibility.md](compatibility.md): compare the new release's `storageFormatVersion` with what
[`SERVER_INFO`](protocol.md#server_info) reports. With the same format, stop the server, replace the jar or the image
and start it on the same folder. With a different one, [dump](#backups), start the new release on an empty folder,
recreate the users, then restore.

## Recovery runbook

Each entry gives the symptom, what the server does by itself and what is left to you.

### After a crash or `kill -9`

Nothing to do. Every acknowledged write is in its collection's write-ahead log, and startup replays the logs, rebuilds
the page rows from the files and re-queues the index work the crash interrupted. Expect `Repaired the field indexes of
...` lines, then a normal start. A start after a crash takes longer than after a clean stop, by the size of the logs it
replays.

### After a power loss

With `syncWrites=true` the same as a crash: a write was acknowledged only once its log record was on the device.
With `syncWrites=false`, writes acknowledged shortly before the power loss may be gone, and nothing tells you which.

### Disk full

Writes fail while the disk is full, reads keep working, and the server stays up. Once you free space everything
resumes without a restart. While full:

- A write answered `500-34` was logged but not applied: it takes effect before the collection is next used, so it may
  already have happened. Check with [`FIND_BY_ID`](protocol.md#find_by_id) before resending it.
- A `COMMIT_TRANSACTION` answered `500-33` committed; its connection keeps applying it with a backoff and its
  collections answer `503-18` until it finishes.
- A read answered `503-19` met a collection with such a write still to apply; retry once space is back.
- Any other error means the write did not happen.

Free space under `filePath` (and `logPath`, if it shares the device), then watch the `Could not checkpoint` lines stop.

### A failed fsync

`lwnrdb_fsync_failures_total` rises and the write that needed the force fails. The server never trusts a file whose
force failed: the next checkpoint rebuilds it from the write-ahead log into a new file, and a log whose own force
failed moves to a new file. Find out why the device refused (a full disk, a failing drive, a network filesystem that
lost its server) before relying on the node again.

### A stale or suspect index

When the startup log names collections with outstanding index work, or a query through an index misses documents a
scan finds, rebuild the indexes from the documents:

```json
{"type":"REINDEX","databaseName":"shop","collectionName":"orders"}
```

`fieldNames` limits it to the named fields ([`REINDEX`](protocol.md#reindex)).

### A damaged primary-key index

When every request to one collection answers an error about its `pk.idx`, or a document the pages hold is not found by
id, rebuild it from the page files:

```json
{"type":"REINDEX","databaseName":"shop","collectionName":"orders","primaryKey":true}
```

It answers `409-15` when a vacuum this process could not clean up after is still open; restart the server, which
recovers it, and run it again.

### Dead-lettered trigger runs

A trigger that fails `triggerMaxAttempts` times is set aside with its last error instead of being dropped.
[`LIST_TRIGGER_RUNS`](protocol.md#list_trigger_runs-admin-only) lists them; fix the cause, then replay or discard each
with [`RESOLVE_TRIGGER_RUN`](protocol.md#resolve_trigger_run-admin-only). Dead runs older than
`triggerDeadLetterRetentionMs`, or past `triggerDeadLetterMaxRuns` per database, are deleted.

### In-doubt distributed transactions

In a cluster, a transaction prepared on several nodes whose coordinator died is resolved by the surviving participants
when any of them knows the outcome, and recovery retries on every membership change. When none knows,
`lwnrdb_in_doubt_transactions` stays above zero and the transaction keeps its locks. Bring the coordinator back if you
can. Otherwise list it with [`LIST_TRANSACTIONS`](protocol.md#list_transactions-admin-only), decide the outcome
from its per-node status, and force it with [`RESOLVE_TRANSACTION`](protocol.md#resolve_transaction-admin-only)
([clustering.md](clustering.md#transactions)).

### A refused start

See [The server refused to start](#the-server-refused-to-start). A refusal changes nothing on disk, so fix the cause and
start again.

### A lost admin password

There is no reset. The server creates an admin from `defaultAdminPassword` only while no admin exists, and never
reads the password from the configuration again. Another admin can set a new one with
[`SET_PASSWORD`](protocol.md#set_password), which is why the [checklist](#deployment-checklist) asks for a second
admin. Without one, the way back is a new server: dump the data as any user who can read it, start a new server on
an empty folder with a new `defaultAdminPassword`, and restore.

## Running a cluster

A cluster node is run like a single server, plus what [clustering.md](clustering.md#operations-runbook) describes:

- every node runs the same release, with the same `clusterSecret` and one shared keystore for cluster TLS;
- `clusterExpectedSize` is the steady-state node count;
- `--dump` against any node reads what that node has applied;
- drain a node's traffic before removing it, since peers wait out `deadTimeoutMs` before taking over its collections.
