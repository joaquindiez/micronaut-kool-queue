# Backlog

Pending and recently completed work. Numbering is stable; once an item is
assigned a number it keeps it across sessions. Closed items stay in the file
with their commit hashes for traceability.

---

## Done

- **#1 — Initializer race fix** · `d53c1a4`
  Two pods starting against an empty database could both pass `tablesExist()`
  and concurrently run `dropAllTables() + createAllTables()`. Now serialized
  with `pg_advisory_xact_lock` (transaction-scoped, auto-released on commit).

- **#2 — queueName end-to-end** · `e57e2b7`
  `processLater(data, queue=...)` accepts a per-call queue, `ApplicationJob`
  exposes an overridable `queue` default, and the worker honors a new
  `queues:` config that filters polling. Empty list = poll all (legacy).

- **#9 — Schema configurable** · `7b38e4c`
  `micronaut.scheduler.kool-queue.schema` lets the host isolate kool-queue's
  tables in a dedicated Postgres schema instead of polluting `public`.
  `KoolQueueTableNames` centralizes qualified names with identifier
  validation; all DDL/DML in the schema service and 6 repos is qualified.

- **AnnotationProcessor exception logging** · `d1ebda4`
  Failures on beans that declare `@KoolQueueTask` methods now surface at ERROR
  with the cause, instead of being swallowed at DEBUG. Unrelated beans that
  fail to load stay at DEBUG via class-level reflection pre-filter.

- **#3 — Real `processId` in `claimed_executions`** · `ffc09ae`
  `KoolQueueScheduler.getProcessIdForTask(name)` exposes the process row id
  for a registered task; `KoolQueueScheduledJob` looks up its own task name
  and stamps that id on every claim instead of hardcoding 0.

- **#4 — Reaper for orphaned claimed jobs** · `82306ee`
  `KoolQueueReaperService.reapOne` reaps one stale process per transaction
  (`FOR UPDATE SKIP LOCKED`), re-enqueues its claims into `ready_executions`,
  and deletes the claims + process row. Driven by the `reapDeadWorkers`
  `@KoolQueueTask` every 30s. Verified at runtime with the synthetic-zombie
  scenario. Threshold floor documented; sample raised from 10s to 60s.

- **#14 — Startup ordering race: tasks registered before schema existed** · `5854f41`
  On a fresh DB the app booted with zero workers: `KoolQueueAnnotationProcessor`
  ran on `StartupEvent` (context) and registered tasks — each synchronously
  writing a process row — before `KoolQueueInitializer` (then on the later
  `ServerStartupEvent`) created the schema, so all tasks failed with
  `relation ... does not exist`. Both now listen to `StartupEvent` with the
  initializer at `HIGHEST_PRECEDENCE`, so the schema is always created first.
  Verified at runtime against an empty DB.

---

## Pending — multi-instance & robustness

- **#5 — Claim atomicity**
  `pollJobsForExecution` (with `FOR UPDATE SKIP LOCKED`) and the subsequent
  `claimedExecutions.save` + `removeFromReady` run in **separate transactions**
  in `KoolQueueScheduledJob.checkPendingTasks`, leaving a microscopic window
  where the lock is released before the move completes. Wrap the three calls
  in a single `@Transactional` so SKIP LOCKED actually protects.

## Pending — features

- **#6 — Retries with exponential backoff**
  A `Result.failure` goes straight to `failed_executions` with no retry. Add
  `attempts` + `next_retry_at` columns, configurable max attempts and backoff
  policy in `finishOnErrorTask`.

- **#7 — Operative `kool_queue_pauses`**
  The pauses table is created but neither producer nor poller consults it.
  Filter polls with `WHERE queue_name NOT IN (SELECT queue_name FROM ...pauses)`
  and expose pause/resume endpoints.

- **#8 — Retention / purge policy**
  `finished_at` is stamped but nothing purges. `kool_queue_jobs` grows forever.
  Configurable periodic task to delete rows where `finished_at < now() - N days`.

- **#12 — Per-queue pollers within the same process**
  Today **a single poller** runs one SQL with `WHERE queue_name IN (...)` and
  a `CASE` for strict priority order. Adding queues to the list does not add
  parallelism, and a slow queue can starve a fast one through the
  `maxConcurrency=5` slots. Register one poller per configured queue (or per
  configured queue group) so each gets its own coroutine slot. Today's
  fairness workaround is multi-process: one app instance per queue.

## Pending — structural / cleanup

- **#10 — `@KoolQueueProducer` cleanup**
  The `KoolQueueProducerInterceptor` only logs before/after. Either give the
  annotation real semantics (e.g. declarative routing:
  `@KoolQueueProducer(queue="emails", priority=10)`) or delete it as dead code.

- **#11 — Real MySQL support**
  README claims MySQL/SQLite support but DDL/DML is Postgres-only:
  `BIGSERIAL`, `RETURNING`, `UUID`, `CREATE INDEX IF NOT EXISTS`,
  `pg_advisory_xact_lock`. Either add a `SqlDialect` strategy with
  Postgres/MySQL classes (5 repos to migrate `RETURNING` → `generatedKeys`,
  `GET_LOCK` for the initializer, UUID as `BINARY(16)`, escape `key`/`value`
  reserved words) or update the README to say PostgreSQL-only.

- **#13 — `jobRefence` lateinit in `ApplicationJob`**
  Pre-existing bug surfaced during #2 testing: `protected lateinit var jobRefence`
  is only initialized on the producer side (inside `processLater`). When a
  worker invokes `processInternal()` on the same `@Singleton` bean in a process
  that never produced for that class, dereferencing `jobRefence` throws
  `UninitializedPropertyAccessException`. Fix: either nullable
  (`var jobRefence: JobReference? = null`), or remove it from bean state
  entirely and only return it as the value of `processLater` (it already does).

---

## Suggested order for next sessions

The order optimizes for "make multi-machine actually trustworthy" first,
then features, then structural cleanup:

1. **#5** claim atomicity (closes a real correctness gap)
2. **#13** jobRefence lateinit fix (low cost, removes a footgun)
3. **#6** retries with backoff (visible user value)
4. **#12** per-queue pollers (only if you hit starvation in real use)
5. **#7 + #8** pauses + retention (operational)
6. **#10** @KoolQueueProducer cleanup
7. **#11** MySQL support (only if there's actual demand)
