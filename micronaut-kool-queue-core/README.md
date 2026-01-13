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


## Resumenes Scheduler

SCHEDULER (threads)  →  Crea jobs recurrentes
↓
ready_executions
↓
DISPATCHER (1s)      →  Mueve scheduled → ready
↓
ready_executions
↓
WORKER (0.1s)        →  Ejecuta jobs

---

## 🔧 Management Endpoints

Kool Queue provides management endpoints for monitoring and administration of the queue scheduler.

### Configuration

To enable the management endpoints, add the following configuration to your `application.yml`:

```yaml
micronaut:
  scheduler:
    kool-queue:
      enable-management-endpoints: true

endpoints:
  kool-queue-scheduler:
    enabled: true
    sensitive: false  # Set to true to require authentication
```

---

### Endpoint: Scheduler Statistics

Returns overall statistics about the KoolQueue scheduler.

**Path:** `GET /kool-queue-scheduler`

```bash
curl -X GET http://localhost:8080/kool-queue-scheduler
```

**Response:**

```json
{
  "activeTasks": 2,
  "maxConcurrentTasks": 4,
  "registeredTasks": ["TestJobs", "EmailJob", "ReportJob"],
  "totalExecutions": 150,
  "successfulExecutions": 145,
  "failedExecutions": 5,
  "successRate": 96.67
}
```

| Field | Type | Description |
|-------|------|-------------|
| `activeTasks` | Integer | Current number of tasks being executed |
| `maxConcurrentTasks` | Integer | Maximum allowed concurrent tasks |
| `registeredTasks` | List | Names of registered job classes |
| `totalExecutions` | Long | Total executions since startup |
| `successfulExecutions` | Long | Total successful executions |
| `failedExecutions` | Long | Total failed executions |
| `successRate` | Double | Success rate percentage (0-100) |

---

### Endpoint: Get Pending Tasks

Returns a list of all jobs pending execution.

**Path:** `GET /kool-queue-scheduler/tasks`

```bash
curl -X GET http://localhost:8080/kool-queue-scheduler/tasks
```

**Response:**

```json
[
  {
    "id": 1,
    "queueName": "default",
    "jobId": "550e8400-e29b-41d4-a716-446655440000",
    "className": "com.example.TestJobs",
    "priority": 0,
    "status": "PENDING",
    "scheduledAt": "2025-01-13T10:30:00",
    "completedAt": null,
    "payload": "{\"data\":\"Hello\"}"
  }
]
```

---

### Endpoint: Get In-Progress Tasks

Returns a list of jobs currently being executed.

**Path:** `GET /kool-queue-scheduler/in-progress`

```bash
curl -X GET http://localhost:8080/kool-queue-scheduler/in-progress
```

**Response:**

```json
[
  {
    "id": 2,
    "queueName": "default",
    "jobId": "550e8400-e29b-41d4-a716-446655440001",
    "className": "com.example.EmailJob",
    "priority": 1,
    "status": "IN_PROGRESS",
    "scheduledAt": "2025-01-13T10:25:00",
    "completedAt": null,
    "payload": "{\"to\":\"user@example.com\"}"
  }
]
```

---

### Endpoints Summary

| Endpoint | Method | Path | Description |
|----------|--------|------|-------------|
| Scheduler Stats | GET | `/kool-queue-scheduler` | Returns scheduler statistics and metrics |
| Pending Tasks | GET | `/kool-queue-scheduler/tasks` | Lists all pending jobs |
| In-Progress Tasks | GET | `/kool-queue-scheduler/in-progress` | Lists currently executing jobs |

---

### Job Status Values

| Status | Description |
|--------|-------------|
| `PENDING` | Job is queued and waiting for execution |
| `IN_PROGRESS` | Job is currently being executed |
| `DONE` | Job completed successfully |
| `ERROR` | Job failed during execution |
