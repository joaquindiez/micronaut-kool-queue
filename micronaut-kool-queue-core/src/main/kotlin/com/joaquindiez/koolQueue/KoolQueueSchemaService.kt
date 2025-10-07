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
   * Crea todas las tablas del sistema Kool Queue
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

    println("✅ Todas las tablas de Kool Queue creadas exitosamente")
  }

  /**
   * 1. TABLA PRINCIPAL: Registro central de todos los jobs
   */
  @Transactional
  open fun createTableQueueJobs() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_jobs (
                id BIGSERIAL PRIMARY KEY,
                queue_name VARCHAR(128) NOT NULL,
                class_name VARCHAR(512) NOT NULL,
                arguments TEXT,                              -- JSON serializado con los argumentos
                priority INT NOT NULL DEFAULT 0,             -- 0 = alta prioridad
                active_job_id UUID NOT NULL UNIQUE,          -- ID único del job
                scheduled_at TIMESTAMP,                      -- NULL = ejecutar ahora, timestamp = ejecutar después
                finished_at TIMESTAMP,                       -- NULL = pendiente, timestamp = completado
                concurrency_key VARCHAR(255),                -- Para concurrency controls
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            
            -- Índices para performance
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_finished_at 
                ON kool_queue_jobs (finished_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_queue_finished 
                ON kool_queue_jobs (queue_name, finished_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_queue_jobs_class_name 
                ON kool_queue_jobs (class_name);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_jobs creada")
  }

  /**
   * 2. READY EXECUTIONS: Jobs listos para ejecutar AHORA (tabla caliente)
   */
  @Transactional
  open fun createTableReadyExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_ready_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK a kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_ready_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE
            );
            
            -- Índices críticos para polling de workers
            -- Índice para poll ALL queues
            CREATE INDEX IF NOT EXISTS idx_kool_ready_poll_all 
                ON kool_queue_ready_executions (priority ASC, job_id ASC);
            
            -- Índice para poll BY QUEUE
            CREATE INDEX IF NOT EXISTS idx_kool_ready_poll_by_queue 
                ON kool_queue_ready_executions (queue_name, priority ASC, job_id ASC);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_ready_executions creada")
  }

  /**
   * 3. SCHEDULED EXECUTIONS: Jobs programados para el futuro
   */
  @Transactional
  open fun createTableScheduledExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_scheduled_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK a kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                scheduled_at TIMESTAMP NOT NULL,             -- Momento de ejecución
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_scheduled_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE
            );
            
            -- Índice para que Dispatcher encuentre jobs que ya es tiempo de ejecutar
            CREATE INDEX IF NOT EXISTS idx_kool_scheduled_dispatch 
                ON kool_queue_scheduled_executions (scheduled_at ASC, priority ASC, job_id ASC);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_scheduled_executions creada")
  }



  /**
   * 4. CLAIMED EXECUTIONS: Jobs siendo ejecutados en este momento
   */
  @Transactional
  open fun createTableClaimedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_claimed_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK a kool_queue_jobs
                process_id BIGINT,                           -- FK a kool_queue_processes
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_claimed_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE
            );
            
            -- Índice para encontrar jobs de un proceso específico
            CREATE INDEX IF NOT EXISTS idx_kool_claimed_process 
                ON kool_queue_claimed_executions (process_id, job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_claimed_executions creada")
  }

  /**
   * 5. FAILED EXECUTIONS: Jobs que fallaron
   */
  @Transactional
  open fun createTableFailedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_failed_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK a kool_queue_jobs
                error TEXT,                                  -- Stack trace y mensaje de error
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_failed_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE
            );
            
            -- Índice para búsquedas por job_id
            CREATE INDEX IF NOT EXISTS idx_kool_failed_job 
                ON kool_queue_failed_executions (job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_failed_executions creada")
  }

  /**
   * 6. PROCESSES: Registro de procesos activos (workers, dispatchers, schedulers)
   */
  @Transactional
  open fun createTableProcesses() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_processes (
                id BIGSERIAL PRIMARY KEY,
                kind VARCHAR(50) NOT NULL,                   -- 'Worker', 'Dispatcher', 'Scheduler'
                name VARCHAR(255) NOT NULL,                  -- 'Worker-1', 'Dispatcher-1'
                pid INT NOT NULL,                            -- Process ID del sistema operativo
                hostname VARCHAR(255),                       -- Nombre del servidor
                supervisor_id BIGINT,                        -- ID del supervisor (si aplica)
                last_heartbeat_at TIMESTAMP NOT NULL,        -- Critical para detectar crashes
                metadata TEXT,                               -- JSON con info adicional
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                -- Unique constraint para evitar duplicados
                CONSTRAINT unique_process_name_supervisor 
                    UNIQUE (name, supervisor_id)
            );
            
            -- Índice para detectar procesos muertos
            CREATE INDEX IF NOT EXISTS idx_kool_processes_heartbeat 
                ON kool_queue_processes (last_heartbeat_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_processes_supervisor 
                ON kool_queue_processes (supervisor_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_processes creada")
  }

  /**
   * 7. RECURRING EXECUTIONS: Para prevenir duplicados en tareas recurrentes
   */
  @Transactional
  open fun createTableRecurringExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_recurring_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL,                      -- FK a kool_queue_jobs
                task_key VARCHAR(255) NOT NULL,              -- Key de la tarea recurrente
                run_at TIMESTAMP NOT NULL,                   -- Momento exacto de esta ejecución
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_recurring_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE,
                
                -- CRÍTICO: Prevenir duplicados con unique constraint
                CONSTRAINT unique_recurring_task_execution 
                    UNIQUE (task_key, run_at)
            );
            
            -- Índice para búsquedas rápidas
            CREATE INDEX IF NOT EXISTS idx_kool_recurring_job 
                ON kool_queue_recurring_executions (job_id);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_recurring_executions creada")
  }

  /**
   * 8. BLOCKED EXECUTIONS: Jobs bloqueados por concurrency limits
   */
  @Transactional
  open fun createTableBlockedExecutions() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_blocked_executions (
                id BIGSERIAL PRIMARY KEY,
                job_id BIGINT NOT NULL UNIQUE,               -- FK a kool_queue_jobs
                queue_name VARCHAR(128) NOT NULL,
                priority INT NOT NULL DEFAULT 0,
                concurrency_key VARCHAR(255) NOT NULL,       -- Key de concurrencia
                expires_at TIMESTAMP NOT NULL,               -- Cuándo expira el bloqueo
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                
                CONSTRAINT fk_blocked_job 
                    FOREIGN KEY (job_id) 
                    REFERENCES kool_queue_jobs(id) 
                    ON DELETE CASCADE
            );
            
            -- Índices para gestión de concurrencia
            CREATE INDEX IF NOT EXISTS idx_kool_blocked_for_release 
                ON kool_queue_blocked_executions (concurrency_key, priority ASC, job_id ASC);
            
            CREATE INDEX IF NOT EXISTS idx_kool_blocked_for_maintenance 
                ON kool_queue_blocked_executions (expires_at, concurrency_key);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_blocked_executions creada")
  }

  /**
   * 9. SEMAPHORES: Control de concurrencia
   */
  @Transactional
  open fun createTableSemaphores() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_semaphores (
                id BIGSERIAL PRIMARY KEY,
                key VARCHAR(255) NOT NULL UNIQUE,            -- Concurrency key
                value INT NOT NULL DEFAULT 1,                -- Slots disponibles
                expires_at TIMESTAMP NOT NULL,               -- Expiración
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            
            -- Índices para performance
            CREATE INDEX IF NOT EXISTS idx_kool_semaphores_expires_at 
                ON kool_queue_semaphores (expires_at);
            
            CREATE INDEX IF NOT EXISTS idx_kool_semaphores_key_value 
                ON kool_queue_semaphores (key, value);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_semaphores creada")
  }

  /**
   * 10. PAUSES: Queues pausadas
   */
  @Transactional
  open fun createTablePauses() {
    val createTableSql = """
            CREATE TABLE IF NOT EXISTS kool_queue_pauses (
                id BIGSERIAL PRIMARY KEY,
                queue_name VARCHAR(128) NOT NULL UNIQUE,     -- Nombre de la queue pausada
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            
            -- Índice para búsquedas rápidas por nombre
            CREATE INDEX IF NOT EXISTS idx_kool_pauses_queue_name 
                ON kool_queue_pauses (queue_name);
        """.trimIndent()

    entityManager.createNativeQuery(createTableSql).executeUpdate()
    println("✅ Tabla kool_queue_pauses creada")
  }

  /**
   * Elimina todas las tablas (útil para testing o reset)
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
    println("✅ Todas las tablas de Kool Queue eliminadas")
  }



  /**
   * Verifica si TODAS las tablas principales ya existen
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

    // Retorna true solo si TODAS las tablas existen
    return count.toInt() == requiredTables.size
  }


  /**
   * Obtiene estadísticas de las tablas
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
        stats[tableName] = -1 // Tabla no existe
      }
    }

    return stats
  }


  /**
   * Verifica qué tablas faltan (útil para debugging)
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


