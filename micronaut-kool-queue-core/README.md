## Kool Queue

# 🧊 Kool Queue — Core Database Schema

Kool Queue is a lightweight, SQL-backed job queue for **Micronaut** applications.  
It provides a solid, observable, and resilient background job system inspired by Solid Queue and Sidekiq Pro patterns.

---

## 📚 The Six Core Tables

| Table | Purpose | Description |
|--------|----------|-------------|
| **kool_queue_jobs** | Permanent record | Stores metadata and lifecycle info for every job ever created. |
| **kool_queue_ready_executions** | Active queue | Holds jobs ready to be executed **now**. Workers poll this table. |
| **kool_queue_scheduled_executions** | Scheduler queue | Contains jobs scheduled to run **later**. |
| **kool_queue_claimed_executions** | Running jobs | Keeps track of jobs that are **currently being executed**. |
| **kool_queue_failed_executions** | Failed jobs | Stores information about **jobs that failed** execution. |
| **kool_queue_processes** | Process monitor | Tracks **live worker processes** via heartbeat (every 60 seconds). |

---

## 🎯 Job Lifecycle (Simplified)

```text
MyJob.perform_later(args)
  ↓
INSERT INTO kool_queue_jobs
INSERT INTO kool_queue_ready_executions
  ↓
Worker picks job:
  INSERT INTO kool_queue_claimed_executions
  DELETE FROM kool_queue_ready_executions
  ↓
Execute MyJob.perform(args)
  ↓
On success:
  UPDATE kool_queue_jobs SET finished_at = NOW
  DELETE FROM kool_queue_claimed_executions
```

Other specialized tables (for advanced features):

- **kool_queue_recurring_executions** → recurring jobs (CRON)
- **kool_queue_blocked_executions** → concurrency limits
- **kool_queue_semaphores** → concurrency locks
- **kool_queue_pauses** → paused queues

---


---

## ✅ Highlights

- **6 Core Tables** managing the full job lifecycle
- **Optimized indexes** for efficient worker polling (`FOR UPDATE SKIP LOCKED`)
- **Heartbeat tracking** for worker processes
- **Transactional schema creation** for safe initialization

---

_© 2025 — Kool Queue for Micronaut_


## Resumens Scheduler

SCHEDULER (threads)  →  Crea jobs recurrentes
↓
ready_executions
↓
DISPATCHER (1s)      →  Mueve scheduled → ready
↓
ready_executions
↓
WORKER (0.1s)        →  Ejecuta jobs
