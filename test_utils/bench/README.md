# Engine benchmark

Runs one workload against lwnrdb, MySQL, PostgreSQL, MongoDB and Couchbase, each in a Docker container with the same
CPU and memory limits, and prints a side-by-side comparison.

```bash
cd test_utils/bench
python3 run_bench.py                                   # all engines, 2 CPUs / 4g each, 100k documents
python3 run_bench.py --engines lwnrdb,mongo --cpus 4 --memory 8g
python3 run_bench.py --docs 500000 --concurrency 16 --ops-scale 2
python3 run_bench.py --durability synced               # every engine syncs every write
python3 report.py results/<run-id>                     # re-render a finished run
```

The host needs only Docker and Python 3.9+. The drivers live in the `bench` client image (`Dockerfile`,
`requirements.txt`), which runs on the compose network beside the engine, so no traffic crosses the Docker Desktop
port forwarder.

## Options

| `run_bench.py` | Default | |
|---|---|---|
| `--engines` | all five | comma-separated subset, run in that order |
| `--cpus` | `2` | CPUs per engine container, pinned to cpuset `0..cpus-1`; the client gets the remaining CPUs |
| `--memory` | `4g` | memory per engine container, swap disabled |
| `--durability` | `relaxed` | `relaxed` or `synced`, applied to every engine (see below) |
| `--keep` | off | leave the last engine running afterwards |
| `--no-build` | off | reuse the built `lwnrdb-bench-server` and `lwnrdb-bench-client` images |

Every other flag is passed to `bench_client.py`: `--docs` (100000), `--batch` (500), `--payload` (512 bytes),
`--concurrency` (8 client processes), `--ops-scale` (1.0), `--warmup` (0.1), `--seed` (42) and `--phases`
(a comma-separated subset; `bulk_insert` always runs, since it loads the data).

## What makes it fair

- **Same box.** Each engine gets the same `cpus`, `cpuset`, `mem_limit` (= `memswap_limit`), `shm_size` and `nofile`
  limits from one YAML anchor. Only one engine runs at a time, on fresh named volumes (`down -v` before and after).
- **Cache sized from the same budget**, following each vendor's own rule for a machine of that size:

  | Engine | Setting | Share of `--memory` |
  |---|---|---|
  | lwnrdb | `-XX:MaxRAMPercentage` / `maxMemory` | 70% heap / 50% cache |
  | MySQL | `innodb_buffer_pool_size` | 50% |
  | PostgreSQL | `shared_buffers` / `effective_cache_size` | 25% / 75% |
  | MongoDB | `wiredTigerCacheSizeGB` | 50% of (memory − 1 GiB), its default formula |
  | Couchbase | data / index service quota | 50% / 15% (min 256 MiB) |

- **Same durability.** `--durability` sets every engine to the same guarantee, and a summary never mixes modes:

  | Engine | `relaxed` (no sync per commit) | `synced` (every write synced) |
  |---|---|---|
  | lwnrdb | `syncWrites=false` | `syncWrites=true` |
  | MySQL | `innodb_flush_log_at_trx_commit=2`, no binlog | `innodb_flush_log_at_trx_commit=1`, no binlog |
  | PostgreSQL | `synchronous_commit=off` | `synchronous_commit=on` |
  | MongoDB | `w:1`, journal flushed every 100 ms | `w:1, j:true` on every write |
  | Couchbase | no durability level | `PERSIST_TO_MAJORITY` (single node: persisted locally) |
- **Same read guarantees.** lwnrdb's index-backed reads are consistent with every acknowledged write, so Couchbase
  queries run with `REQUEST_PLUS` scan consistency rather than its eventually-consistent default.
- **Same data model per engine idiom.** Document stores take the document as is; MySQL and PostgreSQL use a typed
  table with the same eight columns. Every engine indexes `category` and `price` before the load and nothing else
  (Couchbase also gets a primary index, which its unindexed scan needs).
- **Answers are checked**, so an engine cannot win by returning nothing: a read must return the requested id, an
  equality query exactly `limit` rows of that category, a range query `limit` rows sorted and within bounds, every
  update and delete must hit, and the final count must equal `--docs`. Failures are reported per phase.

## Phases

Each phase spreads its operations over `--concurrency` processes that connect, run an untimed warm-up
(`--warmup` × ops), meet at a barrier and then run the timed operations. Throughput is completed operations over
the wall time from the first start to the last finish; latencies are per operation.

| Phase | Operation | Ops at scale 1 |
|---|---|---|
| `bulk_insert` | load `--docs` documents in `--batch` batches (reported as docs/s, no warm-up) | docs / batch |
| `insert` | single insert of a new document | 20,000 |
| `get_by_id` | primary-key lookup of a random loaded document | 50,000 |
| `update_by_id` | full replace of a random loaded document | 20,000 |
| `find_eq_indexed` | equality on indexed `category`, `LIMIT 50` (100 docs per category) | 10,000 |
| `find_range_sorted` | range on indexed `price`, `ORDER BY price LIMIT 50` | 5,000 |
| `count_range_indexed` | count of `price >= x` | 2,000 |
| `scan_count_unindexed` | count of `qty = x`, a full scan | 200 |
| `delete_by_id` | delete every document `insert` created | 20,000 |

## Output

`results/<run-id>/<engine>.json` holds the settings, every phase's throughput, mean/p50/p95/p99/max latency,
errors and invalid results, the container's peak and final memory (`memory.peak` / `memory.current` of its cgroup)
and the size of its data directory at the end. `summary.md` is the rendered comparison; the best value of each row
is in bold.

## Caveats

- Docker Desktop runs everything in one Linux VM; CPU pinning is to VM vCPUs, not physical cores.
- The data directory size includes each engine's logs (InnoDB redo, PostgreSQL WAL, Couchbase index files).
- The client is Python. It runs in separate processes to avoid the GIL, but at very high rates the client, not the
  engine, may be the bottleneck — raise `--concurrency` and check the client CPUs are not saturated.
