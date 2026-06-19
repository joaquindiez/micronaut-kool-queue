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

import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import io.micronaut.core.order.Ordered
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import jakarta.persistence.EntityManager


/**
 * Service for initialization at application startup.
 *
 * Listens to [StartupEvent] (context started) — the same event the
 * [com.joaquindiez.koolQueue.core.KoolQueueAnnotationProcessor] uses to
 * register `@KoolQueueTask` methods. The processor's registration writes a
 * process row synchronously (via the scheduler's thread factory), so the
 * schema MUST exist first. We run at [Ordered.HIGHEST_PRECEDENCE] so this
 * initializer is invoked before the processor; otherwise, on a fresh database,
 * every task fails to register and the app boots with no workers.
 */
@Singleton
open class KoolQueueInitializer(
  private val schemaService: KoolQueueSchemaService,
  private val entityManager: EntityManager
) : ApplicationEventListener<StartupEvent>, Ordered {

  private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)


  override fun onApplicationEvent(event: StartupEvent) {
    init()
  }

  // Run before the annotation processor (default order 0) on the same event.
  override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

  /**
   * Serialized across instances via a Postgres advisory lock so that two pods
   * starting against an empty database cannot both pass the tablesExist() check
   * and run dropAllTables()/createAllTables() concurrently.
   *
   * pg_advisory_xact_lock blocks until acquired and is auto-released on commit,
   * so the second instance enters the lock only after the first has finished
   * creating tables and will then take the "already initialized" branch.
   */
  @Transactional
  open fun init() {
    entityManager
      .createNativeQuery("SELECT pg_advisory_xact_lock($INIT_LOCK_KEY)")
      .singleResult

    if (!schemaService.tablesExist()) {

      logger.info("📦 Deleting previous Kool Queue version...")
      schemaService.dropAllTables()
      logger.info("📦 Initializing Kool Queue...")
      schemaService.createAllTables()

      // Show statistics
      val stats = schemaService.getTableStats()
      logger.info("📊 Table statistics:")
      stats.forEach { (table, count) ->
        logger.info("   - $table: $count records")
      }
    } else {
      logger.info("✅ Kool Queue is already initialized")
    }

    // Reconcile columns added by newer versions. Idempotent (ADD COLUMN IF NOT
    // EXISTS), so it is a no-op on a freshly created schema and brings older
    // databases — where the tables already exist — up to date without a
    // drop/recreate. Runs under the advisory lock taken above.
    schemaService.applyColumnMigrations()
  }

  private companion object {
    // App-specific lock id; identical on every instance so they contend for it.
    private const val INIT_LOCK_KEY: Long = 0x4B4F4F4C5155L  // "KOOLQU"
  }
}