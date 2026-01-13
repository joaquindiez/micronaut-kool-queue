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

import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.domain.KoolQueueScheduledExecution
import com.joaquindiez.koolQueue.domain.TaskStatus
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

@Singleton
open class ScheduledExecutionRepository(
  private val jdbcTemplate: JdbcOperations
) {

  /**
   * RowMapper para convertir ResultSet a KoolQueueReadyExecution
   */
  private fun mapRow(rs: ResultSet): KoolQueueScheduledExecution {
    return KoolQueueScheduledExecution(
      id = rs.getLong("id"),
      jobId = rs.getLong("job_id"),
      queueName = rs.getString("queue_name"),
      priority = rs.getInt("priority"),
      scheduledAt = rs.getTimestamp("scheduled_at").toInstant(),
      createdAt = rs.getTimestamp("created_at").toInstant()
    )
  }

  /**
   * Inserta un nuevo ready execution
   */
  @Transactional
  open fun save(readyExecution: KoolQueueScheduledExecution): KoolQueueScheduledExecution {
    val sql = """
            INSERT INTO kool_queue_scheduled_executions (job_id, queue_name, priority, scheduled_at,created_at)
            VALUES (?, ?, ?, ?, ?)
            RETURNING id, job_id, queue_name, priority,scheduled_at, created_at
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, readyExecution.jobId)
      ps.setString(2, readyExecution.queueName)
      ps.setInt(3, readyExecution.priority)
      ps.setTimestamp(4, Timestamp.from(readyExecution.scheduledAt))
      ps.setTimestamp(5, Timestamp.from(readyExecution.createdAt))

      val rs = ps.executeQuery()
      if (rs.next()) {
        mapRow(rs)
      } else {
        throw RuntimeException("Failed to insert scheduled execution")
      }
    }
  }

  /**
   * Busca por job_id
   */
  fun findByJobId(jobId: Long): KoolQueueScheduledExecution? {
    val sql = """
            SELECT id, job_id, queue_name, priority, scheduled_at, created_at
            FROM kool_queue_scheduled_executions
            WHERE job_id = ?
        """.trimIndent()

    return try {
      jdbcTemplate.prepareStatement(sql) { ps ->
        ps.setLong(1, jobId)
        val rs = ps.executeQuery()
        if (rs.next()) mapRow(rs) else null
      }
    } catch (e: Exception) {
      null
    }
  }

  /**
   * Checks if a scheduled execution exists for the given job_id
   */
  fun existsByJobId(jobId: Long): Boolean {
    val sql = "SELECT 1 FROM kool_queue_scheduled_executions WHERE job_id = ? LIMIT 1"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      val rs = ps.executeQuery()
      rs.next()
    }
  }


  /***
   * Search the jobs pending to be executed
   */
  fun findNextJobsPending(limit: Int = 1): List<KoolQueueScheduledExecution> {
    val sql = """
            SELECT *
            FROM kool_queue_scheduled_executions
            WHERE  scheduled_at <= ?
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { statement ->
      val nowTimeStamp = Timestamp.valueOf(LocalDateTime.now())
      statement.setTimestamp(1, nowTimeStamp)
      statement.setInt(2, limit)

      val resultSet = statement.executeQuery()
      val jobList = mutableListOf<KoolQueueScheduledExecution>()

      while (resultSet.next()) {
        // Map the result set to KoolQueueJobs
        jobList.add(mapRow (resultSet))
      }

      jobList
    }
  }


  /**
   * Elimina por job_id
   * Retorna el número de filas eliminadas
   */
  @Transactional
  open fun deleteByJobId(jobId: Long): Int {
    val sql = "DELETE FROM kool_queue_scheduled_executions WHERE job_id = ?"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      ps.executeUpdate()
    }
  }


}
