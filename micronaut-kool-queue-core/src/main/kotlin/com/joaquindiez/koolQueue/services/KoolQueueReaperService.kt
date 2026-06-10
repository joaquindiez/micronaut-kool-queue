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

import com.joaquindiez.koolQueue.repository.KoolQueueClaimedExecutionsRepository
import com.joaquindiez.koolQueue.repository.ProcessesRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Reaps dead worker processes:
 *
 *   1. Pick one process whose last heartbeat is older than the cutoff,
 *      `FOR UPDATE SKIP LOCKED` so concurrent reapers across instances pick
 *      different rows.
 *   2. Move the jobs that worker had claimed back into `ready_executions`,
 *      so another worker can pick them up on its next poll.
 *   3. Delete the claimed rows and the process row itself.
 *
 * Each reap is its own transaction (one process at a time) so the SKIP LOCKED
 * lock is held only as long as needed and we don't roll back a whole batch
 * because one process's reap fails.
 */
@Singleton
open class KoolQueueReaperService(
  private val processesRepository: ProcessesRepository,
  private val claimedExecutionsRepository: KoolQueueClaimedExecutionsRepository
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  /**
   * Tries to reap one stale process. Returns `null` if there is none to reap.
   *
   * The whole sequence runs inside a single transaction so the
   * SKIP LOCKED lock taken by `findOneDeadProcessId` lives until commit and
   * blocks other reapers from racing on the same row.
   */
  @Transactional
  open fun reapOne(thresholdSeconds: Long): ReapedProcess? {
    val cutoff = Instant.now().minusSeconds(thresholdSeconds)
    val processId = processesRepository.findOneDeadProcessId(cutoff) ?: return null

    val reEnqueued = claimedExecutionsRepository.reEnqueueByProcessId(processId)
    claimedExecutionsRepository.deleteByProcessId(processId)
    processesRepository.deleteProcess(processId)

    logger.debug("Reaped process {} (re-enqueued {} claimed job(s))", processId, reEnqueued)
    return ReapedProcess(processId = processId, reEnqueuedJobs = reEnqueued)
  }
}

data class ReapedProcess(
  val processId: Long,
  val reEnqueuedJobs: Int
)