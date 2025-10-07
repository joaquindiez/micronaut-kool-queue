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
import com.joaquindiez.koolQueue.domain.KoolQueueFailedExecutions
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Timestamp


@Singleton
open class FailedExecutionsRepository(
  private val jdbcTemplate: JdbcOperations
) {
  /**
   * RowMapper para convertir ResultSet a KoolQueueReadyExecution
   */
  private fun mapRow(rs: ResultSet): KoolQueueFailedExecutions {
    return KoolQueueFailedExecutions(
      id = rs.getLong("id"),
      jobId = rs.getLong("job_id"),
      error = rs.getString("error"),
      createdAt = rs.getTimestamp("created_at").toInstant()
    )
  }

  /**
   * Inserta un nuevo ready execution
   */
  @Transactional
  open fun save(failedExecution: KoolQueueFailedExecutions): KoolQueueFailedExecutions {
    val sql = """
            INSERT INTO kool_queue_failed_executions (job_id, errpr, created_at)
            VALUES (?, ?, ?)
            RETURNING id, job_id, errpr, created_at
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, failedExecution.jobId)
      ps.setString(2, failedExecution.error)
      ps.setTimestamp(3, Timestamp.from(failedExecution.createdAt))

      val rs = ps.executeQuery()
      if (rs.next()) {
        mapRow(rs)
      } else {
        throw RuntimeException("Failed to insert claimed execution $failedExecution")
      }
    }
  }

  /**
   * Elimina por job_id
   * Retorna el número de filas eliminadas
   */
  @Transactional
  open fun deleteByJobId(jobId: Long): Int {
    val sql = "DELETE FROM kool_queue_failed_executions WHERE job_id = ?"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      ps.executeUpdate()
    }
  }


}