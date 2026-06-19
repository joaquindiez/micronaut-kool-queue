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
package com.joaquindiez.koolQueue.services

import com.joaquindiez.koolQueue.config.KoolQueueSchedulerConfig
import com.joaquindiez.koolQueue.domain.KoolQueueFailedExecutions
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.domain.KoolQueueReadyExecution
import com.joaquindiez.koolQueue.domain.KoolQueueScheduledExecution
import com.joaquindiez.koolQueue.repository.FailedExecutionsRepository
import com.joaquindiez.koolQueue.repository.JobsRepository
import com.joaquindiez.koolQueue.repository.KoolQueueClaimedExecutionsRepository
import com.joaquindiez.koolQueue.repository.ReadyExecutionRepository
import com.joaquindiez.koolQueue.repository.ScheduledExecutionRepository
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.math.pow

@Singleton
open class KoolQueueJobsService(
  private val jobsRepository: JobsRepository,
  private val readyExecutionRepository: ReadyExecutionRepository,
  private val failedExecutionsRepository: FailedExecutionsRepository,
  private val readyExecutionService: KoolQueueReadyExecutionService,
  private val claimedExecutionsRepository: KoolQueueClaimedExecutionsRepository,
  private val scheduledExecutionRepository: ScheduledExecutionRepository,
  private val schedulerConfig: KoolQueueSchedulerConfig,
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  private val limit = 5

  @Transactional
  open fun findById(id: Long): KoolQueueJobs? {
    return this.jobsRepository.findById(id)
  }

  @Transactional
  open fun findAllTasks(): List<KoolQueueJobs> {
    return this.jobsRepository.findAll()
  }
/*
  @Transactional
  open fun findJobsPending(): List<KoolQueueJobs> {
    return this.jobsRepository.findNextJobsPending()
  }

*/
  @Transactional
  open fun findInProgressTasks(): List<KoolQueueJobs> {
    return this.claimedExecutionsRepository.findAllClaimedJobs()
  }

  @Transactional
  open fun findInProgressTasksPaged(page: Int = 0, size: Int = 20): Map<String, Any> {
    val taskList = this.claimedExecutionsRepository.findAllClaimedJobs(page, size)
    val totalElements = this.claimedExecutionsRepository.countAllClaimedJobs()
    val totalPages = if (size > 0) ((totalElements + size - 1) / size).toInt() else 0

    logger.debug("Result findInProgressTasksPaged page=$page size=$size total=$totalElements")

    return mapOf(
      "content" to taskList,
      "page" to page,
      "size" to size,
      "totalElements" to totalElements,
      "totalPages" to totalPages,
      "hasNext" to (page < totalPages - 1),
      "hasPrevious" to (page > 0)
    )
  }



  /**
   *
   * just for demo purpose
   */
  @Transactional
  open fun addJobReady(job: KoolQueueJobs): KoolQueueJobs {
    val id = this.jobsRepository.save(job)
    val newJob = job.copy(id = id)
    val jobReadyForExecution = readyExecutionService.enqueueAsReady(newJob)
    return newJob
  }


  @Transactional
  open fun enqueueAsScheduled(job: KoolQueueJobs): KoolQueueJobs {
    val id = this.jobsRepository.save(job)
    val newJob = job.copy(id = id)
    val scheduledExecution = KoolQueueScheduledExecution.fromJob(newJob)
    this.scheduledExecutionRepository.save(scheduledExecution)
    return newJob
  }



  @Transactional
  open fun finishSuccessTask(task: KoolQueueJobs): KoolQueueJobs {
    this.jobsRepository.update(task.id!!, LocalDateTime.now())
    this.claimedExecutionsRepository.deleteByJobId(task.id)
    return task
  }

  /**
   * Handles a failed job run: retries with exponential backoff until the
   * attempt budget is exhausted, then dead-letters to `failed_executions`.
   *
   * On a retry the claim is released and the job is re-enqueued as a scheduled
   * execution at `now + backoff`; the scheduled sweep ([findNextScheduledJobsPending])
   * promotes it back to ready once the delay elapses. Only when attempts run
   * out does the job get `finished_at` stamped and a `failed_executions` row.
   *
   * [maxAttemptsOverride] (from `@KoolQueueJob(maxAttempts = ...)`) takes
   * precedence over the global [KoolQueueSchedulerConfig.maxAttempts].
   */
  @Transactional
  open fun finishOnErrorTask(
    task: KoolQueueJobs,
    throwable: Throwable,
    maxAttemptsOverride: Int? = null
  ): KoolQueueJobs {
    val maxAttempts = maxAttemptsOverride ?: schedulerConfig.maxAttempts
    val priorAttempts = task.attempts          // attempts before the run that just failed
    val newAttempts = priorAttempts + 1        // counting the failed run

    this.jobsRepository.updateAttempts(task.id!!, newAttempts)
    this.claimedExecutionsRepository.deleteByJobId(task.id)

    if (newAttempts < maxAttempts) {
      val delaySeconds = retryDelaySeconds(priorAttempts)
      val retryAt = Instant.now().plusSeconds(delaySeconds)
      this.scheduledExecutionRepository.save(
        KoolQueueScheduledExecution(
          jobId = task.id,
          queueName = task.queueName,
          priority = task.priority,
          scheduledAt = retryAt
        )
      )
      logger.warn(
        "Job id=${task.id} failed (attempt $newAttempts/$maxAttempts); retrying in ${delaySeconds}s: ${throwable.message}"
      )
    } else {
      this.jobsRepository.update(task.id, LocalDateTime.now())
      this.failedExecutionsRepository.save(
        KoolQueueFailedExecutions(jobId = task.id, error = "${throwable.message}"))
      logger.error(
        "Job id=${task.id} failed permanently after $newAttempts attempt(s): ${throwable.message}"
      )
    }

    return task
  }

  /**
   * Exponential backoff: `base * 2^priorAttempts`, capped at the configured
   * maximum. `priorAttempts` is 0 for the first retry, so the first delay is
   * exactly the base.
   */
  private fun retryDelaySeconds(priorAttempts: Int): Long {
    val base = schedulerConfig.retryBackoffBaseSeconds.toDouble()
    val cap = schedulerConfig.retryBackoffMaxSeconds.toDouble()
    return min(base * 2.0.pow(priorAttempts), cap).toLong()
  }

/*
  @Transactional
  open fun findNextJobsPending(limit: Int = 1): List<KoolQueueJobs> {

    val taskList = this.jobsRepository.findNextJobsPending(limit = limit)
    logger.debug("Result find Task $taskList")
    for (task in taskList) {
      // Si se encuentra la tarea, se procesa y actualiza su estado
      readyExecutionService.enqueueAsReady(task)
    }
    return taskList
  }
*/
  @Transactional
  open fun findNextScheduledJobsPending(limit: Int = 100): List<KoolQueueScheduledExecution> {

    val taskList = this.scheduledExecutionRepository.findNextJobsPending(limit = limit)
    logger.debug("Result find Task $taskList")
    for (task in taskList) {
      // Si se encuentra la tarea, se procesa y actualiza su estado
      val job = this.jobsRepository.findById(task.jobId) ?: continue
      readyExecutionService.enqueueAsReady(job)
      this.scheduledExecutionRepository.deleteByJobId(task.jobId)
    }
    return taskList
  }


  @Transactional
  open fun findAllJobsPending(): List<KoolQueueJobs> {
    val taskList = this.jobsRepository.findAllJobsPending()
    logger.debug("Result findAllJobsPending  $taskList")
    return taskList
  }

  @Transactional
  open fun findAllJobsPendingPaged(page: Int = 0, size: Int = 20): Map<String, Any> {
    val taskList = this.jobsRepository.findAllJobsPending(page, size)
    val totalElements = this.jobsRepository.countAllJobsPending()
    val totalPages = if (size > 0) ((totalElements + size - 1) / size).toInt() else 0

    logger.debug("Result findAllJobsPendingPaged page=$page size=$size total=$totalElements")

    return mapOf(
      "content" to taskList,
      "page" to page,
      "size" to size,
      "totalElements" to totalElements,
      "totalPages" to totalPages,
      "hasNext" to (page < totalPages - 1),
      "hasPrevious" to (page > 0)
    )
  }

  @Transactional
  open fun countAllJobsPending(): Long {
    return this.jobsRepository.countAllJobsPending()
  }


}
