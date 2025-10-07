package com.joaquindiez.koolQueue.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant


@Entity
@Table(name = "kool_queue_claimed_executions")
data class KoolQueueClaimedExecutions(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  val id: Long? = null,
  @Column(name = "job_id", nullable = false)
  val jobId: Long,
  @Column(name = "process_id", nullable = false)
  val processId: Long,

  @Column(name = "created_at", nullable = false, updatable = false)
val createdAt: Instant = Instant.now()

)
