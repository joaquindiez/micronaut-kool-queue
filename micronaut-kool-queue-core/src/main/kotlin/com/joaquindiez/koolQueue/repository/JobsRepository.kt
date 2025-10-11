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
import com.joaquindiez.koolQueue.domain.TaskStatus
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


  fun update(jobId: Long,  finishAt: LocalDateTime): Int {
    val sql = """
            UPDATE kool_queue_jobs
            SET finished_at = ?
            WHERE id = ?
        """.trimIndent()
    // Execute the update and return the number of affected rows
    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setObject(1, Timestamp.valueOf(finishAt))
      statement.setLong(2, jobId)
      // Execute the update
      statement.executeUpdate()
    }
  }


  fun findAllJobsPending(): List<KoolQueueJobs> {
    val sql = """
            SELECT *
            FROM kool_queue_jobs
            WHERE 
              AND (scheduled_at IS NULL OR scheduled_at <= ?)
            FOR UPDATE SKIP LOCKED
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setString(1, TaskStatus.PENDING.name)
      val nowTimeStamp = Timestamp.valueOf(LocalDateTime.now())
      statement.setTimestamp(2, nowTimeStamp)

      val resultSet = statement.executeQuery()
      val jobList = mutableListOf<KoolQueueJobs>()

      while (resultSet.next()) {
        // Map the result set to KoolQueueJobs
        jobList.add(resultToJob (resultSet))
      }

      jobList
    }
  }

  /*
    fun findInProgressTasks(): List<KoolQueueJobs> {
      val sql = """
              SELECT *
              FROM kool_queue_jobs
              WHERE status = ?
              FOR UPDATE SKIP LOCKED
          """.trimIndent()

      return jdbcTemplate.prepareStatement(sql) { statement ->

        statement.setString(1, TaskStatus.IN_PROGRESS.name)

        val resultSet = statement.executeQuery()
        val jobList = mutableListOf<KoolQueueJobs>()

        while (resultSet.next()) {
          // Map the result set to KoolQueueJobs
          jobList.add(resultToJob (resultSet))
        }

        jobList
      }
    }

    fun findNextJobsPending(limit: Int = 1): List<KoolQueueJobs> {
      val sql = """
              SELECT *
              FROM kool_queue_jobs
              WHERE status = ?
               AND (scheduled_at IS NULL OR scheduled_at <= ?)
              FOR UPDATE SKIP LOCKED
              LIMIT ?
          """.trimIndent()

      return jdbcTemplate.prepareStatement(sql) { statement ->
        statement.setString(1, TaskStatus.PENDING.name)

        val nowTimeStamp = Timestamp.valueOf(LocalDateTime.now())
        statement.setTimestamp(2, nowTimeStamp)
        statement.setInt(3, limit)

        val resultSet = statement.executeQuery()
        val jobList = mutableListOf<KoolQueueJobs>()

        while (resultSet.next()) {
          // Map the result set to KoolQueueJobs
          jobList.add(resultToJob (resultSet))
        }

        jobList
      }
    }

    */


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

  fun findById(id: Long): KoolQueueJobs? {
    val sql = """
            SELECT *
            FROM kool_queue_jobs
            WHERE id = ?
        """.trimIndent()

    return jdbcTemplate.prepareStatement(sql) { statement ->
      statement.setLong(1, id)
      val resultSet = statement.executeQuery()
      if (resultSet.next() ){
        resultToJob (resultSet)
      }else{
        null
      }

    }
  }


  fun save(job: KoolQueueJobs): Long? {
    val sql = """
            INSERT INTO kool_queue_jobs (
                queue_name, class_name, priority, arguments,active_job_id,
                scheduled_at, finished_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

    return jdbcTemplate.execute { connection: Connection ->
      connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { statement ->
        statement.setString(1, job.queueName)
        statement.setString(2, job.className)
        statement.setInt(3, job.priority)
        statement.setString(4, job.arguments)
        statement.setObject(5, job.activeJobId)
        statement.setObject(6, job.scheduledAt?.let { Timestamp.from(it) })
        statement.setObject(7, job.finishedAt?.let { Timestamp.from(it) })
        statement.setObject(8, job.createdAt)
        statement.setObject(9, job.updatedAt)

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
    val scheduledAt: Timestamp? = resultSet.getTimestamp("scheduled_at")
    val job = KoolQueueJobs(
      id = resultSet.getLong("id"),
      className = resultSet.getString("class_name"),
      arguments = resultSet.getString("arguments"),
      priority = resultSet.getInt("priority"),
      queueName = resultSet.getString("queue_name"),
      createdAt = resultSet.getTimestamp("created_at").toLocalDateTime(),
      updatedAt = resultSet.getTimestamp("updated_at").toLocalDateTime(),
      finishedAt = finishedAt?.toInstant(),
      scheduledAt = scheduledAt?.toInstant(),
      activeJobId = UUID.fromString(resultSet.getString("active_job_id"))
      // Mapear otros campos según sea necesario
    )
    return job
  }
}
