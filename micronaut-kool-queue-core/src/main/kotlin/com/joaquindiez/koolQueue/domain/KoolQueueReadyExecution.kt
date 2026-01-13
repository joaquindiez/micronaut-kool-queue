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
   * FK to kool_queue_jobs
   * UNIQUE to avoid duplicates
   */
  @Column(name = "job_id", nullable = false, unique = true)
  val jobId: Long,

  /**
   * Queue name ('default', 'mailers', 'reports', etc.)
   */
  @Column(name = "queue_name", nullable = false, length = 128)
  val queueName: String,

  /**
   * Job priority
   * 0 = highest priority, higher numbers = lower priority
   */
  @Column(name = "priority", nullable = false)
  val priority: Int = 0,

  /**
   * Creation timestamp
   */
  @Column(name = "created_at", nullable = false, updatable = false)
  val createdAt: Instant = Instant.now()
) {

  companion object {
    /**
     * Creates an instance from a KoolQueueJob
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