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

import com.joaquindiez.koolQueue.domain.KoolQueueProcesses
import io.micronaut.data.jdbc.runtime.JdbcOperations
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Singleton
open class ProcessesRepository(
  private val jdbcTemplate: JdbcOperations
) {
  /**
   * RowMapper to convert ResultSet to KoolQueueProcesses
   */
  private fun mapRow(rs: ResultSet): KoolQueueProcesses {
    val hostname = rs.getString("hostname")
    val supervisorId = rs.getLong("supervisor_id")
    val metadata = rs.getString("metadata")

    return KoolQueueProcesses(
      id = rs.getLong("id"),
      kind = rs.getString("kind"),
      name = rs.getString("name"),
      pid = rs.getInt("pid"),
      hostname = if (rs.wasNull()) null else hostname,
      supervisorId = if (rs.wasNull()) null else supervisorId,
      lastHeartbeatAt = rs.getTimestamp("last_heartbeat_at").toInstant(),
      metadata = if (rs.wasNull()) null else metadata,
      createdAt = rs.getTimestamp("created_at").toInstant()
    )
  }

  /**
   * Registers a new process
   */
  @Transactional
  open fun registerProcess(process: KoolQueueProcesses): KoolQueueProcesses {
    val sql = """
            INSERT INTO kool_queue_processes (kind, name, pid, hostname, supervisor_id, last_heartbeat_at, metadata, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id, kind, name, pid, hostname, supervisor_id, last_heartbeat_at, metadata, created_at
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setString(1, process.kind)
      ps.setString(2, process.name)
      ps.setInt(3, process.pid)
      ps.setString(4, process.hostname)
      ps.setObject(5, process.supervisorId)
      ps.setTimestamp(6, Timestamp.from(process.lastHeartbeatAt))
      ps.setString(7, process.metadata)
      ps.setTimestamp(8, Timestamp.from(process.createdAt))

      val rs = ps.executeQuery()
      if (rs.next()) {
        mapRow(rs)
      } else {
        throw RuntimeException("Failed to register process $process")
      }
    }
  }

  /**
   * Updates the heartbeat of a process
   * Returns the number of updated rows
   */
  @Transactional
  open fun updateHeartbeat(processId: Long): Int {
    val sql = """
            UPDATE kool_queue_processes
            SET last_heartbeat_at = ?
            WHERE id = ?
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { ps ->
      ps.setTimestamp(1, Timestamp.from(Instant.now()))
      ps.setLong(2, processId)
      ps.executeUpdate()
    }
  }
}