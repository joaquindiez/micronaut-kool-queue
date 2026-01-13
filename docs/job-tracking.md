# Job Tracking and Status Monitoring

This document describes how to track job status in Kool Queue using the `JobReference` and `KoolQueueJobTracker` APIs.

## Overview

When you enqueue a job using `processLater()`, you now receive a `JobReference` that contains the job's unique identifier. You can use this identifier to query the job's status at any time.

## Quick Start

### 1. Enqueue a Job and Get Reference

```kotlin
@Singleton
class OrderService(
    private val emailJob: SendEmailJob,
    private val jobTracker: KoolQueueJobTracker
) {

    fun processOrder(order: Order): UUID {
        // Enqueue the job and get a reference
        val jobRef = emailJob.processLater(EmailPayload(
            to = order.customerEmail,
            subject = "Order Confirmation",
            body = "Your order #${order.id} has been received."
        ))

        // Store the job ID for later tracking
        order.emailJobId = jobRef.jobId
        orderRepository.save(order)

        return jobRef.jobId
    }
}
```

### 2. Check Job Status

```kotlin
fun checkEmailStatus(order: Order): JobStatus? {
    return order.emailJobId?.let { jobId ->
        jobTracker.getStatus(jobId)?.status
    }
}
```

### 3. Wait for Completion (Optional)

```kotlin
fun processOrderAndWait(order: Order): Boolean {
    val jobRef = emailJob.processLater(payload)

    // Wait up to 30 seconds for completion
    val result = jobTracker.awaitCompletion(
        jobId = jobRef.jobId,
        timeout = Duration.ofSeconds(30)
    )

    return result?.isSuccessful() ?: false
}
```

---

## API Reference

### JobReference

Returned by `processLater()`. Contains the job identifier for tracking.

```kotlin
data class JobReference(
    val jobId: UUID,        // Unique job identifier (UUID v7)
    val className: String,  // Fully qualified class name
    val scheduledAt: Instant? // Scheduled time (null = immediate)
)
```

**Methods:**
- `getSimpleClassName()`: Returns class name without package
- `isScheduled()`: Returns true if job was scheduled for later

---

### JobStatus

Enum representing the current state of a job.

| Status | Description |
|--------|-------------|
| `PENDING` | Job is in the ready queue, waiting for a worker |
| `SCHEDULED` | Job is scheduled for future execution |
| `IN_PROGRESS` | Job is currently being processed by a worker |
| `COMPLETED` | Job finished successfully |
| `FAILED` | Job execution failed |
| `NOT_FOUND` | Job does not exist in the system |

---

### JobStatusInfo

Detailed status information returned by `getStatus()`.

```kotlin
data class JobStatusInfo(
    val jobId: UUID,
    val status: JobStatus,
    val className: String,
    val queueName: String,
    val priority: Int,
    val createdAt: LocalDateTime,
    val scheduledAt: Instant?,
    val finishedAt: Instant?,
    val errorMessage: String?
)
```

**Methods:**
- `isTerminal()`: Returns true if job is COMPLETED or FAILED
- `isSuccessful()`: Returns true if job COMPLETED successfully
- `isFailed()`: Returns true if job FAILED
- `isActive()`: Returns true if job is PENDING, SCHEDULED, or IN_PROGRESS

---

### KoolQueueJobTracker

Service for tracking and querying job status. Inject it into your services.

```kotlin
@Singleton
class KoolQueueJobTracker {

    // Get full status info
    fun getStatus(jobId: UUID): JobStatusInfo?

    // Get only the status enum (lightweight)
    fun getStatusOnly(jobId: UUID): JobStatus

    // Check if job exists
    fun exists(jobId: UUID): Boolean

    // Check if job has finished
    fun isComplete(jobId: UUID): Boolean

    // Wait for job to complete with timeout
    fun awaitCompletion(
        jobId: UUID,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(100)
    ): JobStatusInfo?

    // Get error message for failed jobs
    fun getErrorMessage(jobId: UUID): String?
}
```

---

## Usage Examples

### Example 1: Fire and Forget with Logging

```kotlin
@Singleton
class NotificationService(private val pushJob: SendPushNotificationJob) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun notifyUser(userId: String, message: String) {
        val jobRef = pushJob.processLater(PushPayload(userId, message))
        logger.info("Push notification queued: ${jobRef.jobId}")
    }
}
```

### Example 2: Track Job in Database

```kotlin
@Singleton
class ReportService(
    private val reportJob: GenerateReportJob,
    private val reportRepository: ReportRepository
) {

    fun generateReport(request: ReportRequest): Report {
        val report = Report(
            status = "PROCESSING",
            requestedBy = request.userId
        )
        reportRepository.save(report)

        val jobRef = reportJob.processLater(ReportPayload(report.id))

        report.jobId = jobRef.jobId
        reportRepository.save(report)

        return report
    }
}
```

### Example 3: Synchronous Wait with Timeout

```kotlin
@Singleton
class PaymentService(
    private val paymentJob: ProcessPaymentJob,
    private val jobTracker: KoolQueueJobTracker
) {

    fun processPayment(payment: Payment): PaymentResult {
        val jobRef = paymentJob.processLater(PaymentPayload(payment))

        val status = jobTracker.awaitCompletion(
            jobId = jobRef.jobId,
            timeout = Duration.ofSeconds(60)
        )

        return when {
            status == null -> PaymentResult.Error("Job not found")
            status.isSuccessful() -> PaymentResult.Success(payment.id)
            status.isFailed() -> PaymentResult.Error(status.errorMessage ?: "Unknown error")
            else -> PaymentResult.Pending(jobRef.jobId)
        }
    }
}
```

### Example 4: REST Endpoint for Status Check

```kotlin
@Controller("/jobs")
class JobStatusController(private val jobTracker: KoolQueueJobTracker) {

    @Get("/{jobId}/status")
    fun getJobStatus(jobId: UUID): HttpResponse<JobStatusInfo> {
        val status = jobTracker.getStatus(jobId)
        return if (status != null) {
            HttpResponse.ok(status)
        } else {
            HttpResponse.notFound()
        }
    }
}
```

---

## Architecture

### Job Lifecycle and Status Determination

```
processLater() called
       │
       ▼
┌──────────────────┐
│  kool_queue_jobs │  ← Job record created (activeJobId = UUID)
└────────┬─────────┘
         │
         ▼
    ┌────────────────────────────────────┐
    │ scheduledAt specified?              │
    └────────────────────────────────────┘
         │                    │
        YES                  NO
         │                    │
         ▼                    ▼
┌─────────────────┐  ┌─────────────────────┐
│ scheduled_      │  │ ready_executions    │
│ executions      │  │ (PENDING)           │
│ (SCHEDULED)     │  └──────────┬──────────┘
└────────┬────────┘             │
         │                      │
         │ (when scheduled      │
         │  time arrives)       │
         │                      │
         ▼                      ▼
         └──────────►┌──────────────────────┐
                     │ Worker claims job    │
                     └──────────┬───────────┘
                                │
                                ▼
                     ┌──────────────────────┐
                     │ claimed_executions   │
                     │ (IN_PROGRESS)        │
                     └──────────┬───────────┘
                                │
                                ▼
                     ┌──────────────────────┐
                     │ Job execution        │
                     └──────────┬───────────┘
                                │
              ┌─────────────────┴─────────────────┐
              │                                   │
           SUCCESS                             FAILURE
              │                                   │
              ▼                                   ▼
   ┌──────────────────┐             ┌──────────────────┐
   │ finished_at set  │             │ finished_at set  │
   │ (COMPLETED)      │             │ failed_executions│
   └──────────────────┘             │ record created   │
                                    │ (FAILED)         │
                                    └──────────────────┘
```

### Status Determination Logic

The `KoolQueueJobTracker` determines status by checking tables in this order:

1. **If `finished_at` is set:**
   - Check `failed_executions` table → `FAILED`
   - Otherwise → `COMPLETED`

2. **If in `claimed_executions`:** → `IN_PROGRESS`

3. **If in `scheduled_executions`:** → `SCHEDULED`

4. **If in `ready_executions`:** → `PENDING`

5. **Default:** → `PENDING` (job exists but not yet in any queue)

---

## Best Practices

### 1. Store Job IDs for Critical Operations

For important operations (payments, orders, etc.), always store the job ID:

```kotlin
order.paymentJobId = jobRef.jobId
orderRepository.save(order)
```

### 2. Use Lightweight Status Checks

If you only need the status enum, use `getStatusOnly()`:

```kotlin
val status = jobTracker.getStatusOnly(jobId)  // Fast
// vs
val info = jobTracker.getStatus(jobId)  // More data, slightly slower
```

### 3. Set Appropriate Timeouts

When using `awaitCompletion()`, set realistic timeouts:

```kotlin
// Short-running jobs
jobTracker.awaitCompletion(jobId, Duration.ofSeconds(10))

// Long-running jobs
jobTracker.awaitCompletion(jobId, Duration.ofMinutes(5))
```

### 4. Handle All Terminal States

Always handle both success and failure:

```kotlin
val status = jobTracker.getStatus(jobId)
when {
    status?.isSuccessful() == true -> handleSuccess()
    status?.isFailed() == true -> handleFailure(status.errorMessage)
    else -> handlePending()
}
```

---

## Troubleshooting

### Job Not Found

If `getStatus()` returns `null`:
- Verify the UUID is correct
- Check if the job was deleted or cleaned up
- Ensure the job was successfully enqueued (no exceptions during `processLater()`)

### Job Stuck in PENDING

If a job remains in PENDING status:
- Check if workers are running
- Verify the queue name matches worker configuration
- Check for database connection issues

### Job Stuck in IN_PROGRESS

If a job remains in IN_PROGRESS:
- The worker process may have crashed
- Check `kool_queue_claimed_executions` for stale entries
- Consider implementing a job timeout mechanism
