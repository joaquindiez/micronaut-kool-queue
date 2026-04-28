# Manual Testing Playbook

How to validate Kool-Queue end-to-end against a real Postgres using the
`micronaut-kool-queue-sample` application. Useful when adding features that
touch enqueue/poll/dispatch flows and need to be exercised at runtime —
compilation alone isn't enough.

The sample lives in `micronaut-kool-queue-sample/`. It exposes a small
`TestController` and uses `TestJobs` / `ReportJobs` against the configured
datasource.

---

## Prerequisites

- A running Postgres reachable from the sample.
- The sample's `application.yml` datasource block points at it:

  ```yaml
  datasources:
    default:
      url: jdbc:postgresql://localhost:5432/postgres
      username: postgres
      password: 'changeme'
  ```

If you don't already have one:

```bash
docker run --rm -d --name kqpg -p 5432:5432 \
  -e POSTGRES_PASSWORD=changeme -e POSTGRES_DB=postgres \
  postgres:16
```

A `psql` shortcut for inspection:

```bash
alias kqpsql='docker exec -it kqpg psql -U postgres -d postgres'
```

> **Tip — keep Kool-Queue tables out of `public`**
> Set `micronaut.scheduler.kool-queue.schema: kool_queue` in `application.yml`
> so that all `kool_queue_*` tables land in their own schema. The sample is
> already configured this way, which keeps your host database's `public`
> schema untouched. To inspect tables you'll need to either qualify them
> (`SELECT * FROM kool_queue.kool_queue_jobs`) or set the search path:
>
> ```sql
> SET search_path TO kool_queue, public;
> ```
>
> All the queries below assume you've done this once per psql session, or you
> qualify the table names yourself.

---

## Running the sample

```bash
./gradlew :micronaut-kool-queue-sample:run
```

On first start you should see:

```
✅ Schema kool_queue ready
📦 Initializing Kool Queue...
✅ Table kool_queue.kool_queue_jobs created
... (one line per table)
```

(If `schema:` is not set, the schema line is skipped and the table names appear
without prefix — they go to `public`.)

On subsequent starts:

```
✅ Kool Queue is already initialized
```

Then the scheduler registers its periodic tasks:

```
Task 'checkScheduledTasks' registered ...
Task 'checkReadyTasks' registered ...
```

---

## Endpoints exposed by the sample

| Endpoint              | Job class    | Queue used                          |
|-----------------------|--------------|-------------------------------------|
| `GET /task`           | `TestJobs`   | job's default (`emails`)            |
| `GET /task/report`    | `ReportJobs` | job's default (`reports`)           |
| `GET /task/override`  | `TestJobs`   | per-call override (`vip`)           |
| `GET /task/scheduled` | `TestJobs`   | `emails`, scheduled +1 min          |

---

## Scenario A — multi-queue routing (feature: `queueName` end-to-end)

**Goal:** prove that workers only process jobs for the queues they're configured
to poll, and that producers can choose the queue per-job-class or per-call.

### Setup

`application.yml`:

```yaml
micronaut:
  scheduler:
    kool-queue:
      queues:
        - emails
        - reports
        # NOTE: "vip" intentionally NOT included
```

### Steps

```bash
curl -s localhost:8080/task           # → queue=emails
curl -s localhost:8080/task/report    # → queue=reports
curl -s localhost:8080/task/override  # → queue=vip (should NOT be processed)
```

### Expected

In the application logs:

- `Procesando Test Jobs (queue=emails) -> hello-emails` ✅
- `Procesando Report (queue=reports) -> hello-reports` ✅
- *Nothing* for `hello-vip` — the worker filter excludes `vip`.

In the database:

```sql
SELECT id, queue_name, finished_at IS NOT NULL AS done
FROM kool_queue_jobs
ORDER BY id;
```

Expected: `emails` and `reports` rows have `done=true`, `vip` row has `done=false`.

```sql
SELECT job_id, queue_name FROM kool_queue_ready_executions ORDER BY job_id;
```

Expected: only the `vip` job remains; the others have been consumed.

### Closing the loop

Add `vip` to `queues:`, restart, and verify the previously-stranded `vip` job
gets processed and disappears from `kool_queue_ready_executions`. This rules
out unrelated bugs and confirms it was the queue filter doing the work.

### Cleanup between runs

```sql
SET search_path TO kool_queue, public;
TRUNCATE kool_queue_jobs RESTART IDENTITY CASCADE;
TRUNCATE kool_queue_processes RESTART IDENTITY CASCADE;
```

The `CASCADE` propagates to the execution tables via their FKs.

---

## Scenario C — schema isolation

**Goal:** prove `schema:` config keeps Kool-Queue's tables out of `public`.

### Steps

With the sample's default config (`schema: kool_queue`) running:

```sql
-- Check that public is empty of kool_queue_* tables
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'public' AND table_name LIKE 'kool_queue_%';

-- Check that they exist in the kool_queue schema
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'kool_queue' AND table_name LIKE 'kool_queue_%';
```

Expected: first query returns nothing; second returns all 10 tables.

### Reverting (running without an isolated schema)

Comment out the `schema:` line in `application.yml`. After a restart against a
fresh DB, tables appear in `public` instead. Useful to validate that the
default behavior is unchanged.

---

## Scenario B — multiple instances against the same database

**Goal:** prove `FOR UPDATE SKIP LOCKED` actually distributes work and the
initializer race fix holds with two pods starting against an empty DB.

### Setup

Drop all tables to force a cold start:

```sql
DROP TABLE IF EXISTS
  kool_queue_pauses, kool_queue_semaphores, kool_queue_blocked_executions,
  kool_queue_recurring_executions, kool_queue_processes,
  kool_queue_failed_executions, kool_queue_claimed_executions,
  kool_queue_scheduled_executions, kool_queue_ready_executions,
  kool_queue_jobs CASCADE;
```

### Steps

Launch two sample instances in parallel, each on a different port:

```bash
MICRONAUT_SERVER_PORT=8080 ./gradlew :micronaut-kool-queue-sample:run &
MICRONAUT_SERVER_PORT=8081 ./gradlew :micronaut-kool-queue-sample:run &
```

Watch the logs of both. **Exactly one** should print
`📦 Initializing Kool Queue...`. The other should log
`✅ Kool Queue is already initialized` after the first commits.

Now produce a burst of work:

```bash
for i in $(seq 1 20); do curl -s localhost:8080/task; done
```

Some logs should land in instance A, some in B — proof the queue is shared and
SKIP LOCKED is balancing.

```sql
SELECT hostname, kind, count(*)
FROM kool_queue_processes
GROUP BY hostname, kind;
```

Should show two `checkReadyTasks` rows (one per instance).

---

## Useful inspection queries

Pending work right now:

```sql
SELECT count(*) AS pending FROM kool_queue_ready_executions;
SELECT count(*) AS in_progress FROM kool_queue_claimed_executions;
SELECT count(*) AS scheduled FROM kool_queue_scheduled_executions;
SELECT count(*) AS failed FROM kool_queue_failed_executions;
```

Last 10 jobs:

```sql
SELECT id, queue_name, class_name, finished_at
FROM kool_queue_jobs
ORDER BY id DESC LIMIT 10;
```

Live processes (stale heartbeat = dead worker):

```sql
SELECT id, kind, name, hostname,
       now() - last_heartbeat_at AS heartbeat_age
FROM kool_queue_processes
ORDER BY last_heartbeat_at DESC;
```
