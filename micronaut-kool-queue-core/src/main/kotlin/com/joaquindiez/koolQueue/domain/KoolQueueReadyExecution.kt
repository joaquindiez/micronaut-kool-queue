package com.joaquindiez.koolQueue.domain

import jakarta.persistence.*
import java.time.Instant


@Entity
@Table(name = "kool_queue_ready_executions")
data class KoolQueueReadyExecution(

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  val id: Long? = null,

  /**
   * FK a kool_queue_jobs
   * UNIQUE para evitar duplicados
   */
  @Column(name = "job_id", nullable = false, unique = true)
  val jobId: Long,

  /**
   * Nombre de la queue ('default', 'mailers', 'reports', etc.)
   */
  @Column(name = "queue_name", nullable = false, length = 128)
  val queueName: String,

  /**
   * Prioridad del job
   * 0 = mayor prioridad, números mayores = menor prioridad
   */
  @Column(name = "priority", nullable = false)
  val priority: Int = 0,

  /**
   * Timestamp de creación
   */
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now()
) {

  companion object {
    /**
     * Crea una instancia desde un KoolQueueJob
     */
    fun fromJob(job: KoolQueueJobs): KoolQueueReadyExecution {
      return KoolQueueReadyExecution(
        jobId = job.id!!,
        queueName = job.queueName,
        priority = job.priority
      )
    }
  }

  override fun toString(): String {
    return "ReadyExecution(id=$id, jobId=$jobId, queue='$queueName', priority=$priority)"
  }
}