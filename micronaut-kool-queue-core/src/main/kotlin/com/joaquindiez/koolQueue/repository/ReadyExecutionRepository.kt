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


import com.joaquindiez.koolQueue.domain.KoolQueueReadyExecution
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Timestamp

@Singleton
open class ReadyExecutionRepository(
  private val jdbcTemplate: JdbcOperations
) {

  /**
   * RowMapper para convertir ResultSet a KoolQueueReadyExecution
   */
  private fun mapRow(rs: ResultSet): KoolQueueReadyExecution {
    return KoolQueueReadyExecution(
      id = rs.getLong("id"),
      jobId = rs.getLong("job_id"),
      queueName = rs.getString("queue_name"),
      priority = rs.getInt("priority"),
      createdAt = rs.getTimestamp("created_at").toInstant()
    )
  }

  /**
   * Inserta un nuevo ready execution
   */
  @Transactional
  open fun save(readyExecution: KoolQueueReadyExecution): KoolQueueReadyExecution {
    val sql = """
            INSERT INTO kool_queue_ready_executions (job_id, queue_name, priority, created_at)
            VALUES (?, ?, ?, ?)
            RETURNING id, job_id, queue_name, priority, created_at
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, readyExecution.jobId)
      ps.setString(2, readyExecution.queueName)
      ps.setInt(3, readyExecution.priority)
      ps.setTimestamp(4, Timestamp.from(readyExecution.createdAt))

      val rs = ps.executeQuery()
      if (rs.next()) {
        mapRow(rs)
      } else {
        throw RuntimeException("Failed to insert ready execution")
      }
    }
  }

  /**
   * Busca por job_id
   */
  fun findByJobId(jobId: Long): KoolQueueReadyExecution? {
    val sql = """
            SELECT id, job_id, queue_name, priority, created_at
            FROM kool_queue_ready_executions
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
   * Checks if a ready execution exists for the given job_id
   */
  fun existsByJobId(jobId: Long): Boolean {
    val sql = "SELECT 1 FROM kool_queue_ready_executions WHERE job_id = ? LIMIT 1"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      val rs = ps.executeQuery()
      rs.next()
    }
  }

  /**
   * Elimina por job_id
   * Retorna el número de filas eliminadas
   */
  @Transactional
  open fun deleteByJobId(jobId: Long): Int {
    val sql = "DELETE FROM kool_queue_ready_executions WHERE job_id = ?"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setLong(1, jobId)
      ps.executeUpdate()
    }
  }

  /**
   * Cuenta jobs en una queue específica
   */
  fun countByQueueName(queueName: String): Long {
    val sql = """
            SELECT COUNT(*) 
            FROM kool_queue_ready_executions 
            WHERE queue_name = ?
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setString(1, queueName)
      val rs = ps.executeQuery()
      if (rs.next()) rs.getLong(1) else 0L
    }
  }

  /**
   * Cuenta total de jobs ready
   */
  fun count(): Long {
    val sql = "SELECT COUNT(*) FROM kool_queue_ready_executions"

    return jdbcTemplate.prepareStatement(sql) { ps ->
      val rs = ps.executeQuery()
      if (rs.next()) rs.getLong(1) else 0L
    }
  }

  /**
   * Lista jobs de una queue específica, ordenados por prioridad
   */
  fun findByQueueNameOrderedByPriority(queueName: String): List<KoolQueueReadyExecution> {
    val sql = """
            SELECT id, job_id, queue_name, priority, created_at
            FROM kool_queue_ready_executions
            WHERE queue_name = ?
            ORDER BY priority ASC, job_id ASC
        """.trimIndent()

    val results = mutableListOf<KoolQueueReadyExecution>()

    jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setString(1, queueName)
      val rs = ps.executeQuery()
      while (rs.next()) {
        results.add(mapRow(rs))
      }
    }

    return results
  }

  /**
   * Lista TODOS los jobs ready, ordenados por prioridad
   */
  fun findAllOrderedByPriority(): List<KoolQueueReadyExecution> {
    val sql = """
            SELECT id, job_id, queue_name, priority, created_at
            FROM kool_queue_ready_executions
            ORDER BY priority ASC, job_id ASC
        """.trimIndent()

    val results = mutableListOf<KoolQueueReadyExecution>()

    jdbcTemplate.prepareStatement(sql) { ps ->
      val rs = ps.executeQuery()
      while (rs.next()) {
        results.add(mapRow(rs))
      }
    }

    return results
  }

  /**
   * CRÍTICO: Polling con FOR UPDATE SKIP LOCKED (ALL queues)
   *
   * Este query es el que usan los workers para reclamar jobs.
   * FOR UPDATE SKIP LOCKED permite que múltiples workers consulten
   * simultáneamente sin bloquearse entre sí.
   */
  @Transactional
  open fun pollJobsForUpdate(limit: Int): List<Long> {
    val sql = """
            SELECT job_id 
            FROM kool_queue_ready_executions
            ORDER BY priority ASC, job_id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    val jobIds = mutableListOf<Long>()

    jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setInt(1, limit)
      val rs = ps.executeQuery()
      while (rs.next()) {
        jobIds.add(rs.getLong("job_id"))
      }
    }

    return jobIds
  }

  /**
   * CRÍTICO: Polling con FOR UPDATE SKIP LOCKED (BY QUEUE)
   *
   * Versión que filtra por queue_name específica
   */
  @Transactional
  open fun pollJobsForUpdateByQueue(queueName: String, limit: Int): List<Long> {
    val sql = """
            SELECT job_id 
            FROM kool_queue_ready_executions
            WHERE queue_name = ?
            ORDER BY priority ASC, job_id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    val jobIds = mutableListOf<Long>()

    jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setString(1, queueName)
      ps.setInt(2, limit)
      val rs = ps.executeQuery()
      while (rs.next()) {
        jobIds.add(rs.getLong("job_id"))
      }
    }

    return jobIds
  }

  /**
   * Polling para múltiples queues en orden de prioridad
   * Por ejemplo: ['critical', 'default', 'low']
   *
   * Los jobs se retornan en orden:
   * 1. Primero todos de 'critical'
   * 2. Luego todos de 'default'
   * 3. Finalmente todos de 'low'
   */
  @Transactional
  open fun pollJobsForUpdateByQueues(queueNames: List<String>, limit: Int): List<Long> {
    if (queueNames.isEmpty()) return emptyList()

    // Crear placeholders para IN clause
    val placeholders = queueNames.joinToString(",") { "?" }

    val sql = """
            SELECT job_id, queue_name
            FROM kool_queue_ready_executions
            WHERE queue_name IN ($placeholders)
            ORDER BY 
                CASE queue_name
                    ${queueNames.mapIndexed { index, _ -> "WHEN ? THEN $index" }.joinToString(" ")}
                END,
                priority ASC,
                job_id ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    val jobIds = mutableListOf<Long>()

    jdbcTemplate.prepareStatement(sql) { ps ->
      var paramIndex = 1

      // Set queue names for IN clause
      queueNames.forEach { queueName ->
        ps.setString(paramIndex++, queueName)
      }

      // Set queue names for CASE WHEN
      queueNames.forEach { queueName ->
        ps.setString(paramIndex++, queueName)
      }

      // Set limit
      ps.setInt(paramIndex, limit)

      val rs = ps.executeQuery()
      while (rs.next()) {
        jobIds.add(rs.getLong("job_id"))
      }
    }

    return jobIds
  }

  /**
   * Elimina todos los ready executions (útil para testing)
   */
  @Transactional
  open fun deleteAll(): Int {
    val sql = "DELETE FROM kool_queue_ready_executions"
    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.executeUpdate()
    }
  }
}