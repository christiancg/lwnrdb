# Changelog

Every notable change to the LWNRDB server, its Docker image and its bundled command-line client. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and versions follow [Semantic Versioning](https://semver.org/),
`0.x` for now, so anything may change in a minor release (see [docs/compatibility.md](docs/compatibility.md)).

Each entry names the wire `protocolVersion` and the `storageFormatVersion`, and says when either one moved. A new
storage format means a dump and restore, never just a jar swap. The client libraries under
[`clients/`](clients/README.md) are versioned and released on their own.

## [Unreleased]

The first release. Protocol version 1, storage format version 1.

### Added

- Storage: documents as newline-delimited JSON in paged files that reuse freed space and vacuum in place, a
  write-ahead log per collection with group commit, and durable writes (`syncWrites=true`) synced before they are
  acknowledged. Startup replays the log and finishes interrupted work, so a crash or a power loss never needs a manual
  repair.
- Queries: an aggregation pipeline (`FILTER`, `MAP`, `GROUP_BY`, `JOIN`, `SORT`, `DISTINCT`, `REDUCE`, `COUNT`,
  `SKIP`, `LIMIT`) plus `FIND_BY_ID`, single-field indexes that a query can combine, counts answered from the index,
  explain and analyze with index suggestions, opt-in dirty reads and live queries (`LISTEN`).
- Types: dates and times, geo points with `distance` and `within`, and vectors with an approximate `nearest` search
  (`exact:true` scans instead).
- Integrity: JSON Schema (draft 2020-12) validation per collection, transactions across collections, users with
  database and collection permissions, and database owners.
- Scripting: an embedded JavaScript engine for ad-hoc scripts, stored procedures, before and after triggers, scheduled
  procedures and pipeline operators, sandboxed and limited in time and memory.
- Clustering: fully replicated nodes without a master, an owner per collection, quorum writes, anti-entropy,
  transactions across nodes and scripts placed by load, with optional mutual TLS between nodes.
- Operations:
  - `SERVER_INFO` with the release, `protocolVersion` and `storageFormatVersion`.
  - `GET_DATABASE_STATS`, `REINDEX` and standard error codes.
  - An opt-in Prometheus `/metrics` endpoint (`metricsPort`) with request counts and latencies per operation, error
    codes, connections, fsyncs, cache, queues and data totals.
  - Caching bounded in memory and sized from the heap.
  - TLS for clients.
  - Graceful shutdown.
- Backups: point-in-time snapshots (`START_SNAPSHOT`) that don't block writes, which `--dump` reads through.
- Command-line client: the jar is also its own client, with `--shell`, `--exec`, `--dump` and `--restore` against a
  local or remote server, with or without TLS.
- Storage format stamp: `{filePath}/format.json` records the storage format, and the server refuses to start on a
  folder written in another one instead of misreading it.
- A wire protocol reference for client authors, [docs/protocol.md](docs/protocol.md), with the framing, the request
  and response envelopes, how replies are matched to requests, the retry rules, and every operation, permission and
  error code.
- An operations guide, [docs/operations.md](docs/operations.md), with a deployment checklist, every configuration key,
  sizing, security hardening, metrics and suggested alerts, the log lines that need action, backups and restore
  drills, and a recovery runbook.
- A Docker image for `linux/amd64` and `linux/arm64`, with a trimmed JRE and a health check, published to
  `ghcr.io/christiancg/lwnrdb`.
- Windows support: the jar runs on Windows as well as Linux and macOS, with the same data files and wire protocol
  (lines end in a line feed everywhere). See [On Windows](docs/operations.md#on-windows).

[Unreleased]: https://github.com/christiancg/lwnrdb/commits/main
