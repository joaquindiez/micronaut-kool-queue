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


import io.micronaut.runtime.event.ApplicationStartupEvent
import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.runtime.server.event.ServerStartupEvent
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager
import jakarta.transaction.Transactional


@Singleton
open class DatabaseInitializer(private val entityManager: EntityManager) {

  @EventListener
  fun onStartup(event: ServerStartupEvent) {
    initializeDatabase()
  }


  @Transactional
  open fun initializeDatabase() {
      createTableQueueJobs()
  }

  @Transactional
  open fun createTableQueueJobs() {
    // Define the DDL statement to create the table
    val createTableSql = """
      CREATE TABLE IF NOT EXISTS kool_queue_jobs (
          id SERIAL PRIMARY KEY, -- Use SERIAL for PostgreSQL or INT AUTO_INCREMENT for MySQL
          queue_name VARCHAR(128) NOT NULL,
          class_name VARCHAR(512) NOT NULL,
          priority INT NOT NULL,
          job_id UUID NOT NULL UNIQUE, -- Use UUID data type
          metadata TEXT NOT NULL,
          status VARCHAR(50) NOT NULL,
          scheduled_at TIMESTAMP NULL,
          finished_at TIMESTAMP NULL,
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
      );
        """.trimIndent()

    // Execute the DDL statement
    entityManager.createNativeQuery(createTableSql).executeUpdate()
  }
}
