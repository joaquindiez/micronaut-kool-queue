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

import com.joaquindiez.koolQueue.domain.KoolQueueClaimedExecutions
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.domain.KoolQueueReadyExecution
import com.joaquindiez.koolQueue.repository.KoolQueueClaimedExecutionsRepository
import com.joaquindiez.koolQueue.repository.ReadyExecutionRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class KoolQueueReadyExecutionService(
  private val readyExecutionRepository: ReadyExecutionRepository,
  private val claimedExecutionsRepository: KoolQueueClaimedExecutionsRepository
) {

  /**
   * Enqueues a job as ready (ready to execute)
   */
  @Transactional
  open fun enqueueAsReady(job: KoolQueueJobs): KoolQueueReadyExecution {
    val readyExecution = KoolQueueReadyExecution.fromJob(job)
    return readyExecutionRepository.save(readyExecution)
  }

  /**
   * Moves a job to ready (used by Dispatcher)
   */
  @Transactional
  open fun moveToReady(jobId: Long, queueName: String, priority: Int): KoolQueueReadyExecution {
    val readyExecution = KoolQueueReadyExecution(
      jobId = jobId,
      queueName = queueName,
      priority = priority
    )
    return readyExecutionRepository.save(readyExecution)
  }

  /**
   * Removes a job from ready (when a worker claims it)
   */
  @Transactional
  open fun removeFromReady(jobId: Long): Boolean {
    val deleted = readyExecutionRepository.deleteByJobId(jobId)
    return deleted > 0
  }

  /**
   * Gets jobs ready for processing (ALL queues)
   * Uses FOR UPDATE SKIP LOCKED to avoid conflicts
   */
  @Transactional
  open fun pollJobsForExecution(limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdate(limit)
  }

  /**
   * Gets jobs from a specific queue
   */
  @Transactional
  open fun pollJobsForExecutionByQueue(queueName: String, limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdateByQueue(queueName, limit)
  }

  /**
   * Gets jobs from multiple queues in priority order
   */
  @Transactional
  open fun pollJobsForExecutionByQueues(queueNames: List<String>, limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdateByQueues(queueNames, limit)
  }

  /**
   * Atomically claims up to [limit] ready jobs and returns their job ids.
   *
   * Poll (`FOR UPDATE SKIP LOCKED`) + insert into `claimed_executions` +
   * delete from `ready_executions` all run inside this single transaction, so
   * the SKIP LOCKED row lock taken by the poll is held until commit. That is
   * what makes the claim safe across concurrent workers: previously the poll,
   * the claim insert and the ready delete were three separate transactions, so
   * the lock was released the instant the poll returned, leaving a window where
   * another worker could re-poll the same row and double-claim it.
   *
   * Callers MUST run the actual jobs only after this returns — i.e. outside the
   * transaction — so a (potentially slow) job never executes while this DB
   * transaction is still open holding locks.
   *
   * When [queueNames] is empty, polls across all queues.
   */
  @Transactional
  open fun claimReadyJobs(queueNames: List<String>, processId: Long, limit: Int = 1): List<Long> {
    val jobIds = if (queueNames.isEmpty()) {
      readyExecutionRepository.pollJobsForUpdate(limit)
    } else {
      readyExecutionRepository.pollJobsForUpdateByQueues(queueNames, limit)
    }

    jobIds.forEach { jobId ->
      claimedExecutionsRepository.save(KoolQueueClaimedExecutions(jobId = jobId, processId = processId))
      readyExecutionRepository.deleteByJobId(jobId)
    }

    return jobIds
  }

  /**
   * Gets statistics from the ready table
   */
  fun getStats(): ReadyExecutionStats {
    val total = readyExecutionRepository.count()
    return ReadyExecutionStats(
      totalReady = total
    )
  }

  /**
   * Gets count by queue
   */
  fun countByQueue(queueName: String): Long {
    return readyExecutionRepository.countByQueueName(queueName)
  }
}

/**
 * Statistics for ready executions
 */
data class ReadyExecutionStats(
  val totalReady: Long
)
