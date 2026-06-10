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

- **#5 — Claim atomicity** · `19f8668`
  The poll (`FOR UPDATE SKIP LOCKED`), the `claimed_executions` insert and the
  `ready_executions` delete ran in three separate transactions, so the row lock
  was released the instant the poll returned — a window for a second worker to
  re-poll and double-claim. New `KoolQueueReadyExecutionService.claimReadyJobs`
  does all three in one `@Transactional` (lock held until commit); jobs run only
  after it returns. Verified at runtime: two instances, 60-job burst, each
  processed exactly once, zero duplicate-claim errors.

- **#13 — `jobRefence` lateinit in `ApplicationJob`** · `cc8dd4b`
  `protected lateinit var jobRefence` was per-call producer state on a
  `@Singleton` job bean: concurrent producers clobbered it, and a worker on a
  process that never produced could dereference it uninitialized. Removed the
  field; `processLater` now returns the producer's `JobReference` directly.
  Verified at runtime.

- **#15 — `@KoolQueueJob` declarative job/queue annotation** · `64dbef6`
  Jobs needed `@Singleton` (DI) plus `override val queue` (routing). New
  `@KoolQueueJob(queue = "...")` is meta-annotated with `@Singleton` (Micronaut
  stereotype), so it registers the bean and declares the default queue in one
  place. `ApplicationJob.queue` resolves to the annotation; precedence is
  per-call `processLater(queue=)` > `override val queue` > annotation > default.
  Legacy form still works. Sample + README migrated. Verified at runtime.

- **#6 — Retries with exponential backoff** · `b7777c0`
  Failures went straight to `failed_executions` with no retry. Added an
  `attempts` column; `finishOnErrorTask` now increments it, releases the claim
  and re-enqueues as a scheduled execution at `now + base * 2^priorAttempts`
  (capped) until the budget runs out, then dead-letters. Config:
  `max-attempts` / `retry-backoff-base-seconds` / `retry-backoff-max-seconds`,
  with a per-job `@KoolQueueJob(maxAttempts = N)` override. Reuses the existing
  scheduled sweep — no `next_retry_at` column needed. Verified at runtime
  (flaky-recovers and always-fails-then-dead-letters). See #16 (migration caveat:
  the new column only lands on a fresh DB).

---

## Pending — features

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

- **#16 — No schema migration path** *(surfaced by #6)*
  `KoolQueueInitializer` only runs `dropAllTables()/createAllTables()` when
  `!tablesExist()`, so once the tables exist no DDL change is ever applied.
  When #6 added the `attempts` column, existing databases did not get it and
  the job row mapper breaks (`column "attempts" does not exist`) until the
  schema is dropped/recreated or the column is added manually. Need a real
  migration story: either ship Liquibase/Flyway changelogs, or have the
  initializer reconcile missing columns with idempotent `ALTER TABLE ... ADD
  COLUMN IF NOT EXISTS`. Until then, any new column is a breaking change for
  live deployments.

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

---

## Suggested order for next sessions

The order optimizes for "make multi-machine actually trustworthy" first,
then features, then structural cleanup:

1. **#16** schema migration path (without it, every new column breaks live DBs)
2. **#12** per-queue pollers (only if you hit starvation in real use)
3. **#7 + #8** pauses + retention (operational)
4. **#10** @KoolQueueProducer cleanup
5. **#11** MySQL support (only if there's actual demand)
