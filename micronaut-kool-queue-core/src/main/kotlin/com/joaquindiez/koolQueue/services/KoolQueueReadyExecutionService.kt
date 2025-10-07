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
