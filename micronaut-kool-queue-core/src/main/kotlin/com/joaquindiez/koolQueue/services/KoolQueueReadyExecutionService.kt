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

import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.domain.KoolQueueReadyExecution
import com.joaquindiez.koolQueue.repository.ReadyExecutionRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton

@Singleton
open class KoolQueueReadyExecutionService(
  private val readyExecutionRepository: ReadyExecutionRepository
) {

  /**
   * Encola un job como ready (listo para ejecutar)
   */
  @Transactional
  open fun enqueueAsReady(job: KoolQueueJobs): KoolQueueReadyExecution {
    val readyExecution = KoolQueueReadyExecution.fromJob(job)
    return readyExecutionRepository.save(readyExecution)
  }

  /**
   * Mueve un job a ready (usado por Dispatcher)
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
   * Elimina un job de ready (cuando un worker lo reclama)
   */
  @Transactional
  open fun removeFromReady(jobId: Long): Boolean {
    val deleted = readyExecutionRepository.deleteByJobId(jobId)
    return deleted > 0
  }

  /**
   * Obtiene jobs listos para procesar (ALL queues)
   * Usa FOR UPDATE SKIP LOCKED para evitar conflictos
   */
  @Transactional
  open fun pollJobsForExecution(limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdate(limit)
  }

  /**
   * Obtiene jobs de una queue específica
   */
  @Transactional
  open fun pollJobsForExecutionByQueue(queueName: String, limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdateByQueue(queueName, limit)
  }

  /**
   * Obtiene jobs de múltiples queues en orden de prioridad
   */
  @Transactional
  open fun pollJobsForExecutionByQueues(queueNames: List<String>, limit: Int = 10): List<Long> {
    return readyExecutionRepository.pollJobsForUpdateByQueues(queueNames, limit)
  }

  /**
   * Obtiene estadísticas de la tabla ready
   */
  fun getStats(): ReadyExecutionStats {
    val total = readyExecutionRepository.count()
    return ReadyExecutionStats(
      totalReady = total
    )
  }

  /**
   * Obtiene conteo por queue
   */
  fun countByQueue(queueName: String): Long {
    return readyExecutionRepository.countByQueueName(queueName)
  }
}

/**
 * Estadísticas de ready executions
 */
data class ReadyExecutionStats(
  val totalReady: Long
)
