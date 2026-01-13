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
package com.joaquindiez.koolQueue


import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional


@Singleton
open class KoolQueueSchemaService(private val entityManager: EntityManager) {

  /**
   * Creates all Kool Queue system tables
   */
  @Transactional
  open fun createAllTables() {
    createTableQueueJobs()
    createTableReadyExecutions()
    createTableScheduledExecutions()
    createTableClaimedExecutions()
    createTableFailedExecutions()
    createTableProcesses()
    createTableRecurringExecutions()
    createTableBlockedExecutions()
    createTableSemaphores()
    createTablePauses()

    println("✅ All Kool Queue tables created successfully")
  }

  /**
   * 1. MAIN TABLE: Central registry of all jobs
   */
  @Transactional
  open fun createTableQueueJobs() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_jobs (
                id BIGSERIAL PRIMARY KEY,
                queue_name VARCHAR(128) NOT NULL,
                class_name VARCHAR(512) NOT NULL,
                arguments TEXT,                              -- JSON serialized with arguments
                priority INT NOT NULL DEFAULT 0,             -- 0 = high priority
                active_job_id UUID NOT NULL UNIQUE,          -- Unique job ID
                scheduled_at TIMESTAMP,                      -- NULL = execute now, timestamp = execute later
                finished_at TIMESTAMP,                       -- NULL = pending, timestamp = completed
                concurrency_key VARCHAR(255),                -- For concurrency controls
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            
            -- Indexes for performance
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_finished_at 
                ON kool_queue_jobs (finished_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_queue_finished 
                ON kool_queue_jobs (queue_name, finished_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_class_name 
                ON kool_queue_jobs (class_name);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_jobs created")
  }

  /**
   * 2. READY EXECUTIONS: Jobs ready to execute NOW (hot table)
   */
  @Transactional
  open fun createTableReadyExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_ready_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK to kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_ready_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE
            );

            -- Critical indexes for worker polling
            -- Index for poll ALL queues
            CREATE INDEX IF NOT EXISTS idx_kool_ready_poll_all
                ON kool_queue_ready_executions (priority ASC, job_id ASC);

            -- Index for poll BY QUEUE
            CREATE INDEX IF NOT EXISTS idx_kool_ready_poll_by_queue 
                ON kool_queue_ready_executions (queue_name, priority ASC, job_id ASC);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_ready_executions created")
  }

  /**
   * 3. SCHEDULED EXECUTIONS: Jobs scheduled for the future
   */
  @Transactional
  open fun createTableScheduledExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_scheduled_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK to kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                scheduled_at TIMESTAMP NOT NULL,             -- Execution time
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_scheduled_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE
            );

            -- Index for Dispatcher to find jobs ready to execute
            CREATE INDEX IF NOT EXISTS idx_kool_scheduled_dispatch 
                ON kool_queue_scheduled_executions (scheduled_at ASC, priority ASC, job_id ASC);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_scheduled_executions created")
  }



  /**
   * 4. CLAIMED EXECUTIONS: Jobs being executed right now
   */
  @Transactional
  open fun createTableClaimedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_claimed_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK to kool_queue_jobs
                process_id BIGINT,                           -- FK to kool_queue_processes
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_claimed_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE
            );

            -- Index to find jobs from a specific process
            CREATE INDEX IF NOT EXISTS idx_kool_claimed_process 
                ON kool_queue_claimed_executions (process_id, job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_claimed_executions created")
  }

  /**
   * 5. FAILED EXECUTIONS: Jobs that failed
   */
  @Transactional
  open fun createTableFailedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_failed_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK to kool_queue_jobs
                error TEXT,                                  -- Stack trace and error message
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_failed_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE
            );

            -- Index for searches by job_id
            CREATE INDEX IF NOT EXISTS idx_kool_failed_job 
                ON kool_queue_failed_executions (job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_failed_executions created")
  }

  /**
   * 6. PROCESSES: Registry of active processes (workers, dispatchers, schedulers)
   */
  @Transactional
  open fun createTableProcesses() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_processes (
                id BIGSERIAL PRIMARY KEY,
                kind VARCHAR(50) NOT NULL,                   -- 'Worker', 'Dispatcher', 'Scheduler'
                name VARCHAR(255) NOT NULL,                  -- 'Worker-1', 'Dispatcher-1'
                pid INT NOT NULL,                            -- Operating system Process ID
                hostname VARCHAR(255),                       -- Server name
                supervisor_id BIGINT,                        -- Supervisor ID (if applicable)
                last_heartbeat_at TIMESTAMP NOT NULL,        -- Critical for detecting crashes
                metadata TEXT,                               -- JSON with additional info
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                -- Unique constraint to avoid duplicates
                CONSTRAINT unique_process_name_supervisor
                    UNIQUE (name, supervisor_id)
            );

            -- Index to detect dead processes
            CREATE INDEX IF NOT EXISTS idx_kool_processes_heartbeat 
                ON kool_queue_processes (last_heartbeat_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_processes_supervisor 
                ON kool_queue_processes (supervisor_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_processes created")
  }

  /**
   * 7. RECURRING EXECUTIONS: To prevent duplicates in recurring tasks
   */
  @Transactional
  open fun createTableRecurringExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_recurring_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL,                      -- FK to kool_queue_jobs
                task_key VARCHAR(255) NOT NULL,              -- Recurring task key
                run_at TIMESTAMP NOT NULL,                   -- Exact execution moment
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_recurring_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE,

                -- CRITICAL: Prevent duplicates with unique constraint
                CONSTRAINT unique_recurring_task_execution
                    UNIQUE (task_key, run_at)
            );

            -- Index for fast searches
            CREATE INDEX IF NOT EXISTS idx_kool_recurring_job 
                ON kool_queue_recurring_executions (job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_recurring_executions created")
  }

  /**
   * 8. BLOCKED EXECUTIONS: Jobs blocked by concurrency limits
   */
  @Transactional
  open fun createTableBlockedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_blocked_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK to kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                concurrency_key VARCHAR(255) NOT NULL,       -- Concurrency key
                expires_at TIMESTAMP NOT NULL,               -- When the block expires
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                CONSTRAINT fk_blocked_job
                    FOREIGN KEY (job_id)
                    REFERENCES kool_queue_jobs(id)
                    ON DELETE CASCADE
            );

            -- Indexes for concurrency management
            CREATE INDEX IF NOT EXISTS idx_kool_blocked_for_release 
                ON kool_queue_blocked_executions (concurrency_key, priority ASC, job_id ASC);
            
            CREATE INDEX IF NOT EXISTS idx_kool_blocked_for_maintenance 
                ON kool_queue_blocked_executions (expires_at, concurrency_key);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_blocked_executions created")
  }

  /**
   * 9. SEMAPHORES: Concurrency control
   */
  @Transactional
  open fun createTableSemaphores() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_semaphores (
                id BIGSERIAL PRIMARY KEY,
                key VARCHAR(255) NOT NULL UNIQUE,            -- Concurrency key
                value INT NOT NULL DEFAULT 1,                -- Available slots
                expires_at TIMESTAMP NOT NULL,               -- Expiration
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            -- Indexes for performance
            CREATE INDEX IF NOT EXISTS idx_kool_semaphores_expires_at 
                ON kool_queue_semaphores (expires_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_semaphores_key_value 
                ON kool_queue_semaphores (key, value);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_semaphores created")
  }

  /**
   * 10. PAUSES: Paused queues
   */
  @Transactional
  open fun createTablePauses() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_pauses (
                id BIGSERIAL PRIMARY KEY,
                queue_name VARCHAR(128) NOT NULL UNIQUE,     -- Paused queue name
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            -- Index for fast searches by name
            CREATE INDEX IF NOT EXISTS idx_kool_pauses_queue_name 
                ON kool_queue_pauses (queue_name);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Table kool_queue_pauses created")
  }

  /**
   * Deletes all tables (useful for testing or reset)
   */
  @Transactional
  open fun dropAllTables() {
    val dropTablesSql = """
            DROP TABLE IF EXISTS kool_queue_pauses CASCADE;
            DROP TABLE IF EXISTS kool_queue_semaphores CASCADE;
            DROP TABLE IF EXISTS kool_queue_blocked_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_recurring_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_processes CASCADE;
            DROP TABLE IF EXISTS kool_queue_failed_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_claimed_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_scheduled_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_ready_executions CASCADE;
            DROP TABLE IF EXISTS kool_queue_jobs CASCADE;
        """.trimIndent()

    entityManager.createNativeQuery(dropTablesSql).executeUpdate()
    println("✅ All Kool Queue tables deleted")
  }



  /**
   * Checks if ALL main tables already exist
   */
  @Transactional
  open fun tablesExist(): Boolean {
    val requiredTables = listOf(
      "kool_queue_jobs",
      "kool_queue_ready_executions",
      "kool_queue_scheduled_executions",
      "kool_queue_claimed_executions",
      "kool_queue_failed_executions",
      "kool_queue_processes"
    )

    val query = """
            SELECT COUNT(*) 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
            AND table_name IN (${requiredTables.joinToString(",") { "'$it'" }})
        """.trimIndent()

    val count = entityManager.createNativeQuery(query).singleResult as Long

    // Returns true only if ALL tables exist
    return count.toInt() == requiredTables.size
  }


  /**
   * Gets table statistics
   */
  @Transactional
  open fun getTableStats(): Map<String, Long> {
    val stats = mutableMapOf<String, Long>()

    val tables = listOf(
      "kool_queue_jobs",
      "kool_queue_ready_executions",
      "kool_queue_scheduled_executions",
      "kool_queue_claimed_executions",
      "kool_queue_failed_executions",
      "kool_queue_processes",
      "kool_queue_recurring_executions",
      "kool_queue_blocked_executions",
      "kool_queue_semaphores",
      "kool_queue_pauses"
    )

    tables.forEach { tableName ->
      try {
        val count = entityManager
          .createNativeQuery("SELECT COUNT(*) FROM $tableName")
          .singleResult as Long
        stats[tableName] = count
      } catch (e: Exception) {
        stats[tableName] = -1 // Table does not exist
      }
    }

    return stats
  }


  /**
   * Checks which tables are missing (useful for debugging)
   */
  @Transactional
  open fun getMissingTables(): List<String> {
    val allTables = listOf(
      "kool_queue_jobs",
      "kool_queue_ready_executions",
      "kool_queue_scheduled_executions",
      "kool_queue_claimed_executions",
      "kool_queue_failed_executions",
      "kool_queue_processes",
      "kool_queue_recurring_executions",
      "kool_queue_blocked_executions",
      "kool_queue_semaphores",
      "kool_queue_pauses"
    )

    val query = """
            SELECT table_name 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
            AND table_name IN (${allTables.joinToString(",") { "'$it'" }})
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    val existingTables = entityManager
      .createNativeQuery(query)
      .resultList as List<String>

    return allTables.filter { it !in existingTables }
  }



}


