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
| `--heap-percent` | `35` | `-XX:MaxRAMPercentage` of the lwnrdb container; `maxMemory` is set to half the resulting heap, the README's sizing rule |
| `--java-opts=...` | none | extra JVM flags for lwnrdb, passed as `JDK_JAVA_OPTIONS`; use the equals sign, because a value starting with a dash reads as a flag |
| `--keep` | off | leave the last engine running afterwards |
| `--no-build` | off | reuse the built `lwnrdb-bench-server` and `lwnrdb-bench-client` images |

Every other flag is passed to `bench_client.py`: `--docs` (100000), `--batch` (500), `--payload` (512 bytes),
`--concurrency` (8 client processes), `--ops-scale` (1.0), `--warmup` (0.1), `--seed` (42) and `--phases`
(a comma-separated subset; `bulk_insert` always runs, since it loads the data).

## Memory

lwnrdb's peak container memory follows the heap ceiling, not the data. ZGC grows the heap toward whatever ceiling it is
given, so the same 100k-document run (about 0.7 GiB of live heap after GC) peaked at very different sizes. Mean of three
runs per setting, balanced order, synced, `--ops-scale 2`, 4 GiB container, `maxMemory` fixed at 2 GiB for all of them:

| heap ceiling | peak container memory | CPU per operation vs the old 70% | ZGC allocation stalls |
|---|---:|---|---:|
| 70% (2.8 GiB), the old bench default | 2,722 MiB | baseline | 0 |
| 50% | 2,141 MiB | mixed, inside the noise | 0 |
| 70% with `-XX:SoftMaxHeapSize=1g` | 1,311 MiB | -31% to +8% | 0 |
| 35% (1.4 GiB), the bench default now | 1,563 MiB | -30% to -1% | 0 |
| 25% (1 GiB), the JVM default the Docker image runs with | 1,219 MiB | -25% to +1% | 0 |

A smaller ceiling did not cost CPU or throughput here (`get_by_id` used about 25-30% less CPU per operation under every
smaller ceiling, and the single-document writes the same or less), and none of them stalled an allocation. The image
sets no heap flag, so it runs at the JVM default of a quarter of the container; the bench used to run at 70%, which
reported a memory figure the image never produces. The bench now defaults to 35% with `maxMemory` at half the heap.
Results from before that change used a 70% heap and a 2 GiB `maxMemory`, so their memory column is not comparable.
The `ZGC allocation stalls` row of `summary.md` is read from `-Xlog:gc*:file=/tmp/gc.log`, so it appears only when
that flag is passed through `--java-opts`.

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

## File syscall audit

Throughput hides how much file work one operation does. `test_utils/tools/audit_file_syscalls.py` runs lwnrdb alone
in a container with `strace` attached, seeds 10k documents with two indexes and synced writes, then traces each phase
separately (single insert, update and delete, bulk insert and update, `FIND_BY_ID`, an indexed `FILTER`) and prints
the file syscalls per operation by file kind (`.dat`, `.wal`, `-pk.idx`, `.idxlog`, `.idx`, admin files).

```bash
mvn clean package -DskipTests
python3 test_utils/tools/audit_file_syscalls.py --check            # fail a phase over its budget
python3 test_utils/tools/audit_file_syscalls.py --ops 1000 --keep  # more operations, keep the raw traces
python3 test_utils/tools/audit_file_syscalls.py --budget update=36 --no-build
```

The budgets (`BUDGETS` in the script) are a ratchet: lower one when a change removes calls, never raise one to pass.

## CPU and allocation profile per phase

`test_utils/tools/profile_bench_cpu.py` runs the same workload against a lwnrdb recording with Java Flight Recorder
(a `eclipse-temurin:27-jdk` image around the jar, because the bench image is jlinked without `jdk.jfr`), under the same
CPU and memory limits, and keeps the recording plus a client log stamped line by line. `ProfileReport.java` splits the
samples per phase with those stamps and prints, for each phase, the hottest frames by self time, the same time
attributed to the nearest `org.techhouse` frame (a `HashMap.putVal` is reported under the method that called it),
the inclusive share of project methods, the share of any pattern you name, and the sampled allocation by owner.

```bash
mvn clean package -DskipTests
python3 test_utils/tools/profile_bench_cpu.py mine --docs 100000 --ops-scale 6 --phases insert,update_by_id
java test_utils/tools/ProfileReport.java test_utils/bench/results/profiles/mine.jfr \
    test_utils/bench/results/profiles/mine.log --top 10 CacheSizeEstimator BackgroundProcessorThread
```

`--jar` profiles another build and `--out` moves the files. Unrecognised flags go to `bench_client.py`.
Use `--ops-scale 6` or more for the read and write phases, because one phase at the default scale lasts one or two
seconds and yields a few hundred samples, and `--docs 400000 --phases bulk_insert` for the bulk path.

To compare two builds, alternate them in a balanced order (for example A B B A A B) and run each at least three times:
one phase of an untouched control (`get_by_id`) moved by 20% between identical runs on a laptop, and a build that always
runs second can look slower or faster than it is. Treat a gap under about 10% with overlapping runs as unproven, and
back a throughput difference with the change in a CPU share the profile names.

## Engine CPU per operation

Throughput on a laptop moves 10-20% between identical runs, so each result also carries the CPU the engine container
used per operation. `bench_client.py` prints `PHASE_START <phase>` (and waits one second so the sample lands before the
load) and `PHASE_END <phase> <operations>`; `run_bench.py` and `profile_bench_cpu.py` read the client's output as it
streams and, at each marker, read `usage_usec` from the engine container's `/sys/fs/cgroup/cpu.stat` through
`docker compose exec`. A phase is measured from its own start to the next phase's start (to its end for the last one), so
the index maintenance a write phase leaves behind in the background is billed to that phase. The operations counted are
the timed ones plus the untimed warm-up, and `bulk_insert` counts batches. `summary.md` gets an "Engine CPU per operation"
table, each phase in `lwnrdb.json` gets `cpu_seconds` and `cpu_ms_per_op`, and `profile_bench_cpu.py` prints them and
saves `<name>.cpu.json`. A counter that is unreadable or goes backwards leaves the phase without a value rather than
with a wrong one. `python3 -m unittest discover -s test_utils/bench -p "test_*.py"` runs the tests (a CI leg does too).

Run-to-run spread of the same build, five runs of `run_bench.py --engines lwnrdb --durability synced --ops-scale 2`
(coefficient of variation, population):

| phase | throughput | CPU per operation | CPU / throughput |
|---|---:|---:|---:|
| bulk_insert | 7.1% | 5.5% | 0.77 |
| insert | 13.8% | 4.1% | 0.30 |
| get_by_id | 9.1% | 7.5% | 0.83 |
| update_by_id | 11.3% | 4.8% | 0.43 |
| find_eq_indexed | 9.2% | 7.2% | 0.78 |
| find_range_sorted | 4.0% | 2.9% | 0.72 |
| count_range_indexed | 3.3% | 3.8% | 1.15 |
| scan_count_unindexed | 3.6% | 3.2% | 0.89 |
| delete_by_id | 13.8% | 7.7% | 0.56 |

Reading it: the CPU metric is clearly steadier only for the single-document writes (`insert`, `update_by_id`, and
`delete_by_id` to a lesser degree), which are the phases the write-path work targets. There, claim a gain when the
balanced A/B moves `cpu_ms_per_op` by more than twice its spread (about 8% for insert and 10% for update). For the other
phases it is barely steadier than throughput, so claim a gain only when throughput and CPU per operation agree and
both clear twice their spread. Re-measure the table whenever the host, Docker or the workload changes.
