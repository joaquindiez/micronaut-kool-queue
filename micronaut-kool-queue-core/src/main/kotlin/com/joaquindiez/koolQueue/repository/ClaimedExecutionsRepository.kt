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