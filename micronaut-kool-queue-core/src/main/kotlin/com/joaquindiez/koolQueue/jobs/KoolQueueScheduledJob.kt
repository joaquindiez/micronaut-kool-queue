/**
 * Copyright 2024 Joaquín Díez Gómez
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.joaquindiez.koolQueue.jobs

import com.joaquindiez.koolQueue.config.KoolQueueSchedulerConfig
import com.joaquindiez.koolQueue.core.KoolQueueScheduler
import com.joaquindiez.koolQueue.core.KoolQueueTask

import com.joaquindiez.koolQueue.core.RegisteredTask
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import io.micronaut.context.BeanContext
import io.micronaut.json.JsonMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.persistence.EntityManagerFactory
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import com.joaquindiez.koolQueue.services.KoolQueueReadyExecutionService
import com.joaquindiez.koolQueue.services.KoolQueueReaperService
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream


@Singleton
class KoolQueueScheduledJob(
  private val taskService: KoolQueueJobsService,
  private val readyExecutionService: KoolQueueReadyExecutionService,
  private val jsonMapper: JsonMapper,
  private val applicationContext: ApplicationContext,  // ✅ Added to verify shutdown
  private val schedulerConfig: KoolQueueSchedulerConfig,
  private val scheduler: KoolQueueScheduler,
  private val reaperService: KoolQueueReaperService,
   ) {

  companion object {
    // Task names referenced both by the @KoolQueueTask annotation and at runtime
    // when looking up our own kool_queue_processes.id from the scheduler.
    const val SCHEDULED_TASK_NAME: String = "checkScheduledTasks"
    const val READY_TASK_NAME: String = "checkReadyTasks"
    const val REAPER_TASK_NAME: String = "reapDeadWorkers"

    // Bound on how many dead processes one reaper tick will handle, just to
    // keep tail latency on the periodic task predictable if the table has
    // accumulated a backlog.
    private const val REAPER_MAX_PER_TICK: Int = 50
  }

  @Inject
  lateinit var beanContext: BeanContext

  private val logger = LoggerFactory.getLogger(javaClass)

  /**
   * Periodically reaps workers whose heartbeat has gone stale: any jobs
   * they had claimed are moved back into ready_executions, and the dead
   * process row is removed. Without this, a crashed worker leaves its
   * claimed jobs stuck forever in `claimed_executions` and no one picks
   * them up. Initial delay is intentionally larger than the heartbeat
   * threshold so this worker's own freshly-registered process doesn't
   * accidentally get reaped.
   */
  @KoolQueueTask(name = REAPER_TASK_NAME, interval = "30s", initialDelay = "60s", maxConcurrency = 1)
  fun reapDeadWorkers() {
    if (!applicationContext.isRunning || !isDatabaseAvailable()) return

    val threshold = schedulerConfig.deadWorkerThresholdSeconds
    var reaped = 0
    var reEnqueued = 0

    while (reaped < REAPER_MAX_PER_TICK) {
      val result = try {
        reaperService.reapOne(threshold)
      } catch (e: Exception) {
        handleShutdownAwareException(e, "reaper")
        return
      } ?: break
      reaped++
      reEnqueued += result.reEnqueuedJobs
    }

    if (reaped > 0) {
      logger.info("Reaper: $reaped dead worker(s) reaped, $reEnqueued claimed job(s) re-enqueued")
    }
  }

  @KoolQueueTask(name = SCHEDULED_TASK_NAME, interval = "1s", initialDelay = "10s", maxConcurrency = 1)
  fun checkScheduledTasks(){

    logger.debug("Check Scheduling tasks")
    // ✅ CHECK: Is the application shutting down?
    if (!applicationContext.isRunning) {
      logger.debug("Application is shutting down - skipping pending tasks check")
      return
    }

    // ✅ CHECK: Is the database available?
    if (!isDatabaseAvailable()) {
      logger.debug("Database is not available - skipping pending tasks check")
      return
    }

    this.taskService.findNextScheduledJobsPending(limit = 100).forEach {
      logger.info("Enqueueing scheduled job taskId=${it.id}")
    }
  }

  //@Scheduled(fixedRate = "2s", fixedDelay = "5s")
  @KoolQueueTask(name = READY_TASK_NAME, interval = "0.1s", initialDelay = "10s",  maxConcurrency = 5)
  //context(task: RegisteredTask)
  fun checkPendingTasks() {

    // ✅ CHECK: Is the application shutting down?
    if (!applicationContext.isRunning) {
      logger.debug("Application is shutting down - skipping pending tasks check")
      return
    }

    // ✅ CHECK: Is the database available?
    if (!isDatabaseAvailable()) {
      logger.debug("Database is not available - skipping pending tasks check")
      return
    }

    val configuredQueues = schedulerConfig.queues
    val queueLabel: String = if (configuredQueues.isEmpty()) "ALL" else configuredQueues.toString()

    // Resolve our own kool_queue_processes.id once per poll. The scheduler
    // populates it lazily on the first scheduled tick (inside the executor's
    // ThreadFactory), so by the time this method runs the id should be set.
    // If for some reason it isn't yet, we fall back to 0 — same behaviour as
    // before — but log it so it's visible.
    val workerProcessId = scheduler.getProcessIdForTask(READY_TASK_NAME)
    if (workerProcessId == null) {
      logger.warn("Worker process id for task '$READY_TASK_NAME' is not registered yet; claimed jobs will record process_id=0")
    }
    val claimingProcessId = workerProcessId ?: 0L

    //01. Claim ready jobs atomically: poll (FOR UPDATE SKIP LOCKED) + insert
    // into claimed_executions + delete from ready, all in ONE transaction so
    // the lock is held until commit and a concurrent worker cannot re-poll the
    // same row. Jobs are RUN below, after the claim transaction has committed.
    val claimedJobIds = readyExecutionService.claimReadyJobs(configuredQueues, claimingProcessId, limit = 1)
    logger.debug("Claimed ${claimedJobIds.size} job(s) to run (queues=$queueLabel)")

    for (jobId in claimedJobIds) {
      // ✅ CHECK: State before processing each job
      if (!applicationContext.isRunning || !isDatabaseAvailable()) {
        logger.debug("Application/Database shutting down - stopping job processing")
        return
      }

      val job = taskService.findById(jobId)
      if (job != null) {
        processJobTaskSafely(job)
      } else {
        // Should not happen: the job was just claimed and removed from ready,
        // and ready_executions has an FK to jobs. Log defensively.
        logger.warn("Claimed job not found id=$jobId (already removed from ready)")
      }
    }
  }


  private fun processJobTaskSafely(jobTask: KoolQueueJobs) {
    // Use reflection to get properties from jobTask
    val jobTaskClass = jobTask::class.java
    val classNameField = jobTaskClass.getDeclaredField("className").apply { isAccessible = true }
    val jobIdField = jobTaskClass.getDeclaredField("id").apply { isAccessible = true }
    val metadataField = jobTaskClass.getDeclaredField("arguments").apply { isAccessible = true }

    val className = classNameField.get(jobTask) as String
    val jobId = jobIdField.get(jobTask)
    val metadata = metadataField.get(jobTask) as String

    try {
      val applicationJob = beanContext.getBean(Class.forName(className))
      val inputStream = ByteArrayInputStream(metadata.toByteArray())

      if (applicationJob is ApplicationJob<*>?) {
        // Retrieve the data type
        val dataType = applicationJob.getDataType()
        val koolTaskData = jsonMapper.readValue(inputStream, dataType)

        try {
          val result = applicationJob.processInternal(koolTaskData!!)

          result.fold(
            onSuccess = {
              logger.info("Job taskId=$jobId className=$className finished successfully")

              // ✅ SAFE UPDATE: Verify before updating DB
              safeUpdateJobStatus(jobTask, jobId.toString(), "success") {
                taskService.finishSuccessTask(jobTask)
              }
            },
            onFailure = {
              logger.error("Job taskId=$jobId className=$className finished onError")

              safeUpdateJobStatus(jobTask, jobId.toString(), "failure") {
                taskService.finishOnErrorTask(jobTask, it)
              }
            }
          )

        } catch (ex: Exception) {
          logger.error("Job taskId=$jobId className=$className UnExpected failure", ex)

          safeUpdateJobStatus(jobTask, jobId.toString(), "unexpected error") {
            taskService.finishOnErrorTask(jobTask, ex)
          }
        }

      } else {
        logger.error("Job className=$className not valid taskId=$jobId")

        safeUpdateJobStatus(jobTask, jobId.toString(), "invalid job class") {
          taskService.finishOnErrorTask(jobTask, Error("Job className=$className not valid taskId=$jobId") )
        }

      }

    } catch (e: Exception) {
      val jobId = try {
        jobTaskClass.getDeclaredField("jobId").apply { isAccessible = true }.get(jobTask)
      } catch (ex: Exception) { "unknown" }

      logger.error("Error processing job taskId=$jobId className=$className", e)

      safeUpdateJobStatus(jobTask, jobId.toString(), "processing error") {
        taskService.finishOnErrorTask(jobTask, e)
      }
    }
  }

  // ✅ SAFE METHOD: Only updates DB if available
  private fun safeUpdateJobStatus(jobTask: Any, jobId: String, operation: String, updateAction: () -> Unit) {
    try {
      // Verify state before attempting update
      if (!applicationContext.isRunning) {
        logger.debug("Skipping job status update for $jobId ($operation) - application shutting down")
        return
      }

      if (!isDatabaseAvailable()) {
        logger.debug("Skipping job status update for $jobId ($operation) - database not available")
        return
      }

      // Execute the update
      updateAction()

    } catch (e: Exception) {
      handleShutdownAwareException(e, "job status update for $jobId ($operation)")
    }
  }

  // ✅ CHECK: Is the database available?
  private fun isDatabaseAvailable(): Boolean {
    return try {
      val entityManagerFactory = applicationContext.findBean(EntityManagerFactory::class.java)
      entityManagerFactory.isPresent && entityManagerFactory.get().isOpen
    } catch (e: Exception) {
      false
    }
  }

  // ✅ SMART HANDLING: Distinguish shutdown errors vs real errors
  private fun handleShutdownAwareException(e: Exception, operation: String) {
    when {
      e.message?.contains("EntityManagerFactory is closed") == true -> {
        logger.debug("EntityManagerFactory closed during $operation - application shutting down")
      }
      e.message?.contains("Connection pool closed") == true -> {
        logger.debug("Connection pool closed during $operation - application shutting down")
      }
      e.message?.contains("HikariPool") == true && e.message?.contains("shutdown") == true -> {
        logger.debug("HikariPool shutdown during $operation")
      }
      e.message?.contains("HHH000026: Second-level cache disabled") == true -> {
        logger.debug("Hibernate cache disabled during $operation - likely shutdown")
      }
      !applicationContext.isRunning -> {
        logger.debug("Error during $operation while shutting down: ${e.message}")
      }
      else -> {
        // Es un error real, no de shutdown
        logger.error("Error during $operation", e)
      }
    }
  }


}
