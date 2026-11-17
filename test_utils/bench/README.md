# Engine benchmark

Runs the same workload against lwnrdb, MySQL, PostgreSQL, MongoDB and Couchbase, each in a Docker container with the
same CPU and memory limits, and prints a side-by-side comparison.

```bash
cd test_utils/bench
python3 run_bench.py                                   # all engines, 2 CPUs and 4g each, 100k documents
python3 run_bench.py --engines lwnrdb,mongo --cpus 4 --memory 8g
python3 run_bench.py --docs 500000 --concurrency 16 --ops-scale 2
python3 run_bench.py --durability synced               # every engine syncs every write
python3 report.py results/<run-id>                     # render a finished run again
```

The host only needs Docker and Python 3.9 or newer. The drivers live in the `bench` client image, which runs on the
same compose network as the engine, so no traffic goes through the Docker Desktop port forwarder.

## Options

| `run_bench.py` | Default | |
|---|---|---|
| `--engines` | all five | comma-separated subset, run in that order |
| `--cpus` | `2` | CPUs per engine container, pinned to cpuset `0..cpus-1`; the client gets the rest |
| `--memory` | `4g` | memory per engine container, swap disabled |
| `--durability` | `relaxed` | `relaxed` or `synced`, applied to every engine (see below) |
| `--heap-percent` | `35` | `-XX:MaxRAMPercentage` of the lwnrdb container; `maxMemory` is set to half the heap |
| `--java-opts=...` | none | extra JVM flags for lwnrdb; use the equals sign, since a value starting with a dash reads as a flag |
| `--keep` | off | leave the last engine running afterwards |
| `--no-build` | off | reuse the built `lwnrdb-bench-server` and `lwnrdb-bench-client` images |

Every other flag goes to `bench_client.py`: `--docs` (100000), `--batch` (500), `--payload` (512 bytes),
`--concurrency` (8 client processes), `--ops-scale` (1.0), `--warmup` (0.1), `--bulk-warmup` (0.5), `--seed` (42) and
`--phases` (a comma-separated subset; `bulk_insert` always runs, since it loads the data).

The lwnrdb server runs G1, like the Docker image, and logs its collections to `/tmp/gc.log`. The G1 pause and full GC
rows of `summary.md` come from that log. Results from before the bench moved from a 70% heap with a 2 GiB `maxMemory`
to the current defaults have a memory column that isn't comparable with later runs.

## What makes it fair

- Same box. Every engine gets the same `cpus`, `cpuset`, `mem_limit` (equal to `memswap_limit`), `shm_size` and
  `nofile` limits. Only one engine runs at a time, on fresh volumes.
- Cache sized from the same budget, following each vendor's own rule for a machine that size:

  | Engine | Setting | Share of `--memory` |
  |---|---|---|
  | lwnrdb | `-XX:MaxRAMPercentage` / `maxMemory` | `--heap-percent` heap / half of it as cache |
  | MySQL | `innodb_buffer_pool_size` | 50% |
  | PostgreSQL | `shared_buffers` / `effective_cache_size` | 25% / 75% |
  | MongoDB | `wiredTigerCacheSizeGB` | 50% of (memory - 1 GiB), its default formula |
  | Couchbase | data / index service quota | 50% / 15% (min 256 MiB) |

- Same durability. `--durability` gives every engine the same guarantee, and a summary never mixes modes:

  | Engine | `relaxed` (no sync per commit) | `synced` (every write synced) |
  |---|---|---|
  | lwnrdb | `syncWrites=false` | `syncWrites=true` |
  | MySQL | `innodb_flush_log_at_trx_commit=2`, no binlog | `innodb_flush_log_at_trx_commit=1`, no binlog |
  | PostgreSQL | `synchronous_commit=off` | `synchronous_commit=on` |
  | MongoDB | `w:1`, journal flushed every 100 ms | `w:1, j:true` on every write |
  | Couchbase | no durability level | `PERSIST_TO_MAJORITY` (single node: persisted locally) |

- Same read guarantees. lwnrdb's index reads see every acknowledged write, so Couchbase queries run with
  `REQUEST_PLUS` scan consistency instead of its eventually consistent default.
- Same data in each engine's own idiom. Document stores take the document as is, and MySQL and PostgreSQL use a typed
  table with the same eight columns. Every engine indexes `category` and `price` before the load and nothing else
  (Couchbase also gets the primary index its unindexed scan needs).
- Answers are checked, so an engine can't win by returning nothing. A read must return the requested id, an equality
  query exactly `limit` rows of that category, a range query `limit` rows sorted and within bounds, every update and
  delete must hit, and the final count must equal `--docs`. Failures are reported per phase.

## Phases

Each phase spreads its operations over `--concurrency` processes. They connect, run an untimed warm-up (`--warmup`
times the ops), wait for each other and then run the timed operations. Throughput is completed operations over the
time from the first start to the last finish, and latencies are per operation.

| Phase | Operation | Ops at scale 1 |
|---|---|---|
| `bulk_insert` | load `--docs` documents in batches of `--batch`, reported as docs/s; a `--bulk-warmup` share of the batches loads first, untimed | docs / batch |
| `insert` | insert one new document | 20,000 |
| `get_by_id` | look up a random loaded document by primary key | 50,000 |
| `update_by_id` | replace a random loaded document | 20,000 |
| `find_eq_indexed` | equality on indexed `category`, `LIMIT 50` (100 docs per category) | 10,000 |
| `find_range_sorted` | range on indexed `price`, `ORDER BY price LIMIT 50` | 5,000 |
| `count_range_indexed` | count of `price >= x` | 2,000 |
| `scan_count_unindexed` | count of `qty = x`, a full scan | 200 |
| `delete_by_id` | delete every document `insert` created | 20,000 |

## Output

`results/<run-id>/<engine>.json` has the settings and, per phase, the throughput, mean/p50/p95/p99/max latency, errors,
invalid results and the engine CPU per operation, plus the container's peak and final memory and the size of its data
directory at the end. `summary.md` is the rendered comparison, with the best value of each row in bold.

## Caveats

- Docker Desktop runs everything in one Linux VM, so CPU pinning is to VM vCPUs, not physical cores.
- The data directory size includes each engine's logs (InnoDB redo, PostgreSQL WAL, Couchbase index files).
- The client is Python. It runs in separate processes to avoid the GIL, but at very high rates the client can be the
  bottleneck instead of the engine. Raise `--concurrency` and check the client CPUs are not saturated.

## Engine CPU per operation

Throughput on a laptop moves 10-20% between identical runs, so each result also has the CPU the engine container used
per operation (`cpu_ms_per_op`), read from its cgroup at the start and end of each timed phase. The background work a
write phase leaves behind is billed to that phase. Results from before 2026-11-07 included the warm-up, so they aren't
comparable with later ones. `python3 -m unittest discover -s test_utils/bench -p "test_*.py"` runs the bench's own
tests.

Run-to-run spread of the same build, five runs of `run_bench.py --engines lwnrdb --durability synced --ops-scale 2`
(coefficient of variation):

| phase | throughput | CPU per operation |
|---|---:|---:|
| bulk_insert | 7.1% | 5.5% |
| insert | 13.8% | 4.1% |
| get_by_id | 9.1% | 7.5% |
| update_by_id | 11.3% | 4.8% |
| find_eq_indexed | 9.2% | 7.2% |
| find_range_sorted | 4.0% | 2.9% |
| count_range_indexed | 3.3% | 3.8% |
| scan_count_unindexed | 3.6% | 3.2% |
| delete_by_id | 13.8% | 7.7% |

The CPU figure is clearly steadier than throughput only for the single-document writes. For those, a change is real
when `cpu_ms_per_op` moves by more than twice its spread (about 8% for insert and 10% for update). For the other
phases, claim a gain only when throughput and CPU per operation agree and both move by more than twice their spread.
Measure the table again whenever the host, Docker or the workload changes.

## Comparing two builds

Alternate them in a balanced order (for example A B B A A B) and run each at least three times. An untouched phase
moved by 20% between identical runs on a laptop, and a build that always runs second can look slower or faster than it
is. Treat a gap under about 10% with overlapping runs as unproven, and back a throughput difference with a change the
CPU profile shows.

## File syscall audit

`test_utils/tools/audit_file_syscalls.py` runs lwnrdb alone in a container under `strace`, loads 10k documents with two
indexes and synced writes, then traces each kind of operation on its own and prints the file syscalls per operation by
file kind, the time spent in syncs, and the write-ahead log checkpoints. With `--concurrency N` the phases run from N
clients, and `ops per .wal sync` shows the real group commit size. Durations are measured under `strace`, so only
compare them with each other.

```bash
mvn clean package -DskipTests
python3 test_utils/tools/audit_file_syscalls.py --check            # fail a phase over its budget
python3 test_utils/tools/audit_file_syscalls.py --ops 1000 --keep  # more operations, keep the raw traces
python3 test_utils/tools/audit_file_syscalls.py --budget update=36 --no-build
python3 test_utils/tools/audit_file_syscalls.py --concurrency 8 --ops 2000 --no-build
```

The budgets in the script only go down: lower one when a change removes calls, never raise one to make it pass.

## CPU and allocation profile per phase

`test_utils/tools/profile_bench_cpu.py` runs the same workload against lwnrdb with Java Flight Recorder on, under the
same limits, and keeps the recording and a timestamped client log. `ProfileReport.java` splits the samples per phase
and prints the hottest frames, the time attributed to the nearest project method, and the sampled allocations. Any
extra names you pass get their own share.

```bash
mvn clean package -DskipTests
python3 test_utils/tools/profile_bench_cpu.py mine --docs 100000 --ops-scale 6 --phases insert,update_by_id
java test_utils/tools/ProfileReport.java test_utils/bench/results/profiles/mine.jfr \
    test_utils/bench/results/profiles/mine.log --top 10 CacheSizeEstimator BackgroundProcessorThread
```

`--jar` profiles another build and `--out` moves the files. Other flags go to `bench_client.py`. Use `--ops-scale 6`
or more for the read and write phases, since a phase at the default scale lasts a second or two, and
`--docs 400000 --phases bulk_insert` for bulk loading. To follow a hot frame to its callers, export the samples with
`jfr print --json --stack-depth 64 --events jdk.ExecutionSample mine.jfr`, because the default depth of 5 hides them.
