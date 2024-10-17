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
package freesoullabs.koolQueue.repository

import freesoullabs.koolQueue.domain.KoolQueueJobs
import freesoullabs.koolQueue.domain.TaskStatus
import io.micronaut.data.jdbc.runtime.JdbcOperations
import jakarta.inject.Singleton
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.*


@Singleton
class JobsRepository (private val jdbcTemplate: JdbcOperations) {

  fun update(jobId: Long, newStatus: TaskStatus): Int {
    val sql = """
            UPDATE kool_queue_jobs
            SET status = ?
            WHERE id = ?
        """.trimIndent()

    // Execute the update and return the number of affected rows
    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setString(1, newStatus.name)
      statement.setLong(2, jobId)

      // Execute the update
      statement.executeUpdate()
    }
  }

  fun update(jobId: Long, newStatus: TaskStatus, finishAt: LocalDateTime): Int {
    val sql = """
            UPDATE kool_queue_jobs
            SET status = ?, finished_at = ?
            WHERE id = ?
        """.trimIndent()
    // Execute the update and return the number of affected rows
    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setString(1, newStatus.name)
      statement.setObject(2, Timestamp.valueOf(finishAt))
      statement.setLong(3, jobId)
      // Execute the update
      statement.executeUpdate()
    }
  }

  fun findNextJobsPending(): List<KoolQueueJobs> {
    val sql = """
            SELECT *
            FROM kool_queue_jobs
            WHERE status = ?
            FOR UPDATE SKIP LOCKED
            LIMIT ?
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setString(1, TaskStatus.PENDING.name)
      statement.setInt(2, 5)

      val resultSet = statement.executeQuery()
      val jobList = mutableListOf<KoolQueueJobs>()

      while (resultSet.next()) {
        // Map the result set to KoolQueueJobs
        jobList.add(resultToJob (resultSet))
      }

      jobList
    }
  }

  fun findAll(): List<KoolQueueJobs> {
    val sql = """
            SELECT *
            FROM kool_queue_jobs
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { statement ->
      val resultSet = statement.executeQuery()
      val jobList = mutableListOf<KoolQueueJobs>()

      while (resultSet.next()) {
        // Map the result set to KoolQueueJobs
        jobList.add(resultToJob (resultSet))
      }

      jobList
    }
  }



  fun save(job: KoolQueueJobs): Long? {
    val sql = """
            INSERT INTO kool_queue_jobs (
                queue_name, job_id, class_name, priority, metadata, status,
                scheduled_at, finished_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    return jdbcTemplate.execute { connection: Connection ->
      connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { statement ->
        statement.setString(1, job.queueName)
        statement.setObject(2, job.jobId)
        statement.setString(3, job.className)
        statement.setInt(4, job.priority)
        statement.setString(5, job.metadata)
        statement.setString(6, job.status.name)
        statement.setObject(7, job.scheduledAt)
        statement.setObject(8, job.finishedAt)
        statement.setObject(9, job.createdAt)
        statement.setObject(10, job.updatedAt)

        statement.executeUpdate()

        // Retrieve the generated key (usually the primary key)
        val generatedKeys = statement.generatedKeys
        if (generatedKeys.next()) {
          generatedKeys.getLong(1) // Return the generated ID
        } else {
          null
        }
      }
    }

    }


  private fun resultToJob(resultSet: ResultSet): KoolQueueJobs {
    val finishedAt: Timestamp? = resultSet.getTimestamp("finished_at")
    val scheduledAt: Timestamp?   = resultSet.getTimestamp("scheduled_at")
    val job = KoolQueueJobs(
      id = resultSet.getLong("id"),
      className = resultSet.getString("class_name"),
      status = TaskStatus.valueOf(resultSet.getString("status")),
      jobId = UUID.fromString(resultSet.getString("job_id")),
      metadata = resultSet.getString("metadata"),
      priority = resultSet.getInt("priority"),
      queueName = resultSet.getString("queue_name"),
      createdAt = resultSet.getTimestamp("created_at").toLocalDateTime(),
      updatedAt = resultSet.getTimestamp("updated_at").toLocalDateTime(),
      finishedAt = finishedAt?.toLocalDateTime(),
      scheduledAt = scheduledAt?.toLocalDateTime()
      // Mapear otros campos según sea necesario
    )
    return job
  }
}
