# Benchmarking Kool Queue

This is the harness used to find and fix the throughput bottlenecks described
below, plus a Solid Queue counterpart so the two can be compared on equal
terms. Everything here is reproducible: same machine, same database, same
method on both sides.

## What is measured, and why

**End-to-end throughput**: wall clock from the first enqueue to the last job
finishing, divided by the number of jobs.

That choice matters. The obvious metric — time the enqueue phase, then time the
drain — stops being meaningful as soon as the worker keeps up with the
producer, because jobs drain *while* they are still being enqueued. Timing the
drain separately then measures only the tail and reports absurd rates (an early
version of this harness produced "30,000 jobs/s" that way). End-to-end is well
defined whether or not the phases overlap, and it is what a user actually
experiences.

Two payloads are used, and they answer different questions:

- **Empty job** (`BENCH_SLEEP_MS=0`) — measures the queue machinery alone:
  enqueue, claim, dispatch, execute, bookkeeping.
- **Job with real work** (e.g. `BENCH_SLEEP_MS=100`) — the realistic case. The
  gap between the measured rate and the theoretical `threads / duration` is the
  per-job overhead the queue adds.

The benchmark also asserts that exactly N jobs ran, never more. Parallel
execution makes double-claiming a real risk, and a throughput number from a
run that processed some jobs twice would be worthless.

## Prerequisites

A PostgreSQL reachable on `localhost:5432`. Not Testcontainers: container
startup would be billed to the measurement, and the Ruby side needs to point at
the very same database.

```bash
# Debian/Ubuntu
apt-get install -y postgresql
pg_ctlcluster 16 main start
su postgres -c "psql -c \"ALTER USER postgres PASSWORD 'changeme';\""
```

Override the defaults with `BENCH_DB_URL`, `BENCH_DB_USER` and
`BENCH_DB_PASSWORD` if your setup differs.

## Running the Kool Queue benchmark

```bash
./gradlew :micronaut-kool-queue-sample:benchmark
```

It is a JUnit test tagged `benchmark`, so `./gradlew test` skips it — it needs
a live database and takes minutes. Configuration lives in
`micronaut-kool-queue-sample/src/test/resources/application-benchmark.yml`.

| Variable | Default | Meaning |
|----------|---------|---------|
| `BENCH_JOBS` | `300` | Jobs per repetition. |
| `BENCH_REPS` | `3` | Repetitions. The first is always slower — the JIT is cold — so compare warm ones. |
| `BENCH_SLEEP_MS` | `0` | Artificial work inside each job. |
| `BENCH_PRODUCERS` | `1` | Enqueueing threads. Raise it if the producer becomes the bottleneck (see below). |
| `BENCH_LABEL` | `kool-queue` | Tag printed on each result line. |

```bash
# Empty jobs: the machinery alone
BENCH_JOBS=300 BENCH_SLEEP_MS=0 ./gradlew :micronaut-kool-queue-sample:benchmark

# Realistic jobs
BENCH_JOBS=150 BENCH_SLEEP_MS=100 ./gradlew :micronaut-kool-queue-sample:benchmark
```

Results are printed as `BENCHRUN` (per repetition) and `BENCHSTAT` (mean,
standard deviation, min, max).

**Drop the schema between runs**, or leftover rows will skew the next one:

```bash
su postgres -c "psql -c 'DROP SCHEMA IF EXISTS kool_queue CASCADE;'"
```

## Running the Solid Queue benchmark

```bash
gem install solid_queue pg
cd benchmark/solid_queue
BENCH_JOBS=300 BENCH_SLEEP_MS=0 BENCH_THREADS=5 ruby bench.rb
```

Same variables, same output format, same database. It creates its own
`solid_bench` schema and drops it on each run. `BENCH_THREADS` is Solid Queue's
worker thread count and should match Kool Queue's `job-execution-threads` for
the comparison to mean anything.

The script boots a minimal Rails application because Solid Queue ships a
`Rails::Engine` and cannot be required without a host application.

## Two traps worth knowing about

**The producer can become the bottleneck.** Once the consumer is fast, a
single-threaded producer caps the measurement and you end up benchmarking the
harness. Symptom: the end-to-end rate stops responding to consumer-side
changes. Check the producer's own rate — for Kool Queue it is roughly
1,150 jobs/s warm on one thread, for Solid Queue around 275/s — and if the
end-to-end figure is approaching it, raise `BENCH_PRODUCERS`.

**Some changes do not show up in throughput at all.** Reducing database round
trips barely moves the needle with PostgreSQL on the same machine, where a
round trip is nearly free, and with a single worker, where nobody is waiting on
the locks. For those, count statements instead:

```bash
su postgres -c "psql -c \"ALTER SYSTEM SET log_statement = 'all';\" -c 'SELECT pg_reload_conf();'"
: > /var/log/postgresql/postgresql-16-main.log
# ... run the benchmark ...
grep -c 'INSERT INTO kool_queue.kool_queue_claimed_executions' /var/log/postgresql/postgresql-16-main.log
su postgres -c "psql -c \"ALTER SYSTEM SET log_statement = 'none';\" -c 'SELECT pg_reload_conf();'"
```

That count is deterministic, unlike a throughput delta the JIT noise eats.

## Reference results

PostgreSQL 16.13, 5 execution threads, single-threaded producer, 3 repetitions,
logging at WARN. Numbers are `mean ± standard deviation` in jobs/second.

### The optimisation history

Each step is a commit on `claude/perf-claim-batch-and-pool`:

| | empty job | 100 ms job |
|---|---:|---:|
| Baseline | 10.0 | 9.9 |
| Claim by free capacity + execution pool | 49.9 | 24.9 |
| Wake the poller when a job finishes | 463.9 | 47.0 |

The baseline row is the tell: an empty job and a 100 ms job performed
identically, which is the signature of a system bound by the polling clock
rather than by work. The poller claimed one job per 0.1 s tick, so a worker was
capped at ~10 jobs/s no matter how much concurrency was configured.

The bulk-claim step that followed reduced database statements from 603 to 213
per 200 jobs (2.8x fewer) without moving end-to-end throughput, for the reasons
in the second trap above.

### Versus Solid Queue 1.7.0

| | Kool Queue | Solid Queue | |
|---|---:|---:|---:|
| 100 ms job | 47.5 ± 0.8 | 39.4 ± 0.5 | 1.2x |
| Empty job | 507.6 ± 133.2 | 81.2 ± 1.9 | 6.3x |
| Enqueue only | ~1,150 | 275 | 4.2x |

Expressed as per-job overhead outside the job body (`threads / rate`, minus the
job duration): about 5 ms for Kool Queue, about 27 ms for Solid Queue.

Read these carefully:

- The architectures are now equivalent in the hot loop — claim by free
  capacity, execute in a pool, wake on completion. What the gap measures is
  mostly platform: JVM against Ruby, hand-written SQL against ActiveRecord,
  which loads, deserialises, updates and destroys a record per job.
- **This compares one worker process against one worker process, which flatters
  the JVM.** Ruby has a GVL, so Solid Queue scales in production by running
  several worker processes under its supervisor, not by raising thread counts.
  A realistic deployment of 4 processes × 5 threads would look different. Kool
  Queue has no multi-process supervisor; it scales inside one JVM.
- PostgreSQL is on the same machine, so network latency — which would penalise
  the chattier design — is absent.
