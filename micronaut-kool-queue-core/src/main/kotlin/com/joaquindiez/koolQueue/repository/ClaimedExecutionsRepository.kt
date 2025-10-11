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
package com.joaquindiez.koolQueue.repository

import com.joaquindiez.koolQueue.domain.KoolQueueClaimedExecutions
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Timestamp


@Singleton
open class KoolQueueClaimedExecutionsRepository(
  private val jdbcTemplate: JdbcOperations
) {
  /**
   * RowMapper para convertir ResultSet a KoolQueueReadyExecution
   */
  private fun mapRow(rs: ResultSet): KoolQueueClaimedExecutions {
    return KoolQueueClaimedExecutions(
      id = rs.getLong("id"),
      jobId = rs.getLong("job_id"),
      processId = rs.getLong("process_id"),
      createdAt = rs.getTimestamp("created_at").toInstant()
    )
  }

  /**
   * Inserta un nuevo ready execution
   */
  @Transactional
  open fun save(claimedExecution: KoolQueueClaimedExecutions): KoolQueueClaimedExecutions {
    val sql = """
            INSERT INTO kool_queue_claimed_executions (job_id, process_id, created_at)
            VALUES (?, ?, ?)
            RETURNING id, job_id, process_id, created_at
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, claimedExecution.jobId)
      ps.setLong(2, claimedExecution.processId)
      ps.setTimestamp(3, Timestamp.from(claimedExecution.createdAt))

      val rs = ps.executeQuery()
      if (rs.next()) {
        mapRow(rs)
      } else {
        throw RuntimeException("Failed to insert claimed execution $claimedExecution")
      }
    }
  }

  /**
   * Elimina por job_id
   * Retorna el número de filas eliminadas
   */
  @Transactional
  open fun deleteByJobId(jobId: Long): Int {
    val sql = "DELETE FROM kool_queue_claimed_executions WHERE job_id = ?"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      ps.executeUpdate()
    }
  }


  /**
   * Elimina por job_id
   * Retorna el número de filas eliminadas
   */
  @Transactional
  open fun findAll(): List<KoolQueueClaimedExecutions> {
    val sql = "SELECT * FROM kool_queue_claimed_executions"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      val resultSet = ps.executeQuery()
      val jobList = mutableListOf<KoolQueueClaimedExecutions>()

      while (resultSet.next()) {
        // Map the result set to KoolQueueJobs
        jobList.add(mapRow (resultSet))
      }

      jobList
    }
  }

  /**
   * Finds all jobs that are currently claimed (in progress)
   * by joining with the jobs table
   */
  @Transactional
  open fun findAllClaimedJobs(): List<KoolQueueJobs> {
    val sql = """
      SELECT j.*
      FROM kool_queue_jobs j
      INNER JOIN kool_queue_claimed_executions ce ON j.id = ce.job_id
    """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      val resultSet = ps.executeQuery()
      val jobList = mutableListOf<KoolQueueJobs>()

      while (resultSet.next()) {
        jobList.add(mapRowToJob(resultSet))
      }

      jobList
    }
  }

  /**
   * Maps a ResultSet row to a KoolQueueJobs object
   */
  private fun mapRowToJob(rs: ResultSet): KoolQueueJobs {
    val finishedAt: Timestamp? = rs.getTimestamp("finished_at")
    val scheduledAt: Timestamp? = rs.getTimestamp("scheduled_at")
    return KoolQueueJobs(
      id = rs.getLong("id"),
      className = rs.getString("class_name"),
      arguments = rs.getString("arguments"),
      priority = rs.getInt("priority"),
      queueName = rs.getString("queue_name"),
      createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
      updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
      finishedAt = finishedAt?.toInstant(),
      scheduledAt = scheduledAt?.toInstant(),
      activeJobId = java.util.UUID.fromString(rs.getString("active_job_id"))
    )
  }


}