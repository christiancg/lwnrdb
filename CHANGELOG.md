# Changelog

Every notable change to the LWNRDB server, its Docker image and its bundled command-line client. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/),
`0.x` for now, so anything may change in a minor release (see [docs/compatibility.md](docs/compatibility.md)).

Each entry names the wire `protocolVersion` and the `storageFormatVersion`, and says when either one moved. A moved
storage format means dump and restore, never a jar swap. The client libraries under [`clients/`](clients/README.md) are
versioned and released on their own.

## [Unreleased]

The first release. Protocol version 1, storage format version 1.

### Added

- **Storage.** Documents as newline-delimited JSON in paged files with first-fit reuse and in-place page vacuums, a
  per-collection write-ahead log with group commit, and durable writes (`syncWrites=true`) that are forced before they
  are acknowledged. Startup replays the log, settles interrupted vacuums and rebuilds page occupancy, so a crash or a
  power loss never needs a manual repair.
- **Queries.** An aggregation pipeline (`FILTER`, `MAP`, `GROUP_BY`, `JOIN`, `SORT`, `DISTINCT`, `REDUCE`, `COUNT`,
  `SKIP`, `LIMIT`) plus `FIND_BY_ID`, single-field indexes the planner combines freely, index-only counts, explain and
  analyze with index suggestions, opt-in dirty reads and listenable queries (`LISTEN`).
- **Types.** Dates and times, geo points with `distance` and `within`, and vectors with an approximate `nearest`
  search (`exact:true` scans).
- **Integrity.** Per-collection JSON Schema (draft 2020-12) validation, multi-collection transactions, users with
  database and collection permissions, and database owners.
- **Scripting.** An embedded JavaScript engine for ad-hoc scripts, stored procedures, triggers (before and after the
  write), scheduled procedures and pipeline operators, sandboxed and bounded in time and memory.
- **Clustering.** Fully replicated nodes without a master, per-collection ownership, quorum writes, anti-entropy,
  cross-node transactions and load-based script placement, with optional mutual TLS between nodes.
- **Operations.**
  - `SERVER_INFO` with the release, `protocolVersion` and `storageFormatVersion`.
  - `GET_DATABASE_STATS`, `REINDEX` and standardized error codes.
  - An opt-in Prometheus `/metrics` endpoint (`metricsPort`): request counts and latencies per operation, error
    codes, connections, fsyncs, cache, queues and data totals.
  - Memory-bounded caching sized from the heap.
  - TLS for clients.
  - Graceful shutdown.
- **Backups.** Non-blocking point-in-time snapshots (`START_SNAPSHOT`), which `--dump` reads through while writes go on.
- **Command-line client.** The jar is also its own client: `--shell`, `--exec`, `--dump` and `--restore`, against a
  local or remote server, with or without TLS.
- **Storage format stamp.** `{filePath}/format.json` records the storage format, and the server refuses to start on a
  folder written in another one instead of misreading it.
- **Docker image.** For `linux/amd64` and `linux/arm64`, with a trimmed JRE and a health check, published to
  `ghcr.io/christiancg/lwnrdb`.

[Unreleased]: https://github.com/christiancg/lwnrdb/commits/main
