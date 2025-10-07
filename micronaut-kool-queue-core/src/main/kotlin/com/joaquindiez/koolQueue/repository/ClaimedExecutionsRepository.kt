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


}