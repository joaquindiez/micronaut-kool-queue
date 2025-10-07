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

import io.micronaut.runtime.event.annotation.EventListener
import io.micronaut.runtime.server.event.ServerStartupEvent
import jakarta.inject.Singleton


/**
 * Servicio para inicialización al arrancar la aplicación
 */
@Singleton
class KoolQueueInitializer(
  private val schemaService: KoolQueueSchemaService
) {

  private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)


  @EventListener
  fun onStartup(event: ServerStartupEvent) {
    init()
  }

  fun init() {
    if (!schemaService.tablesExist()) {

      logger.info("📦 Borrando version anterior Kool Queue...")
      schemaService.dropAllTables()
      logger.info("📦 Inicializando Kool Queue...")
      schemaService.createAllTables()

      // Mostrar estadísticas
      val stats = schemaService.getTableStats()
      logger.info("📊 Estadísticas de tablas:")
      stats.forEach { (table, count) ->
        logger.info("   - $table: $count registros")
      }
    } else {
      logger.info("✅ Kool Queue ya está inicializado")
    }
  }
}