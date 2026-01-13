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

import java.time.LocalDateTime

@Singleton
open class KoolQueueJobsService(
  private val jobsRepository: JobsRepository,
  private val readyExecutionRepository: ReadyExecutionRepository,
  private val failedExecutionsRepository: FailedExecutionsRepository,
  private val readyExecutionService: KoolQueueReadyExecutionService,
  private val claimedExecutionsRepository: KoolQueueClaimedExecutionsRepository,
  private val scheduledExecutionRepository: ScheduledExecutionRepository,
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

  @Transactional
  open fun finishOnErrorTask(task: KoolQueueJobs, throwable: Throwable): KoolQueueJobs {
    this.jobsRepository.update(task.id!!, LocalDateTime.now())
    this.claimedExecutionsRepository.deleteByJobId(task.id)
    this.failedExecutionsRepository.save(
      KoolQueueFailedExecutions(jobId = task.id, error = "${throwable.message}"))

    return task
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
