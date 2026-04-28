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
package com.joaquindiez.koolQueue.config

import io.micronaut.context.annotation.ConfigurationProperties
import io.micronaut.context.annotation.Context


@ConfigurationProperties("micronaut.scheduler.kool-queue")
@Context
data class KoolQueueSchedulerConfig(
  /**
   * Enables or disables the scheduler completely
   */
  var enabled: Boolean = true,

  /**
   * Maximum number of tasks that can run simultaneously
   */
  var maxConcurrentTasks: Int = 2,

  /**
   * Default interval between task executions
   * Format: Duration (e.g.: "30s", "5m", "1h")
   */
  var defaultInterval: String = "30s",

  /**
   * Default initial delay before the first execution
   * Format: Duration (e.g.: "10s", "1m")
   */
  var defaultInitialDelay: String = "10s",

  /**
   * Maximum time in seconds to wait during graceful shutdown
   */
  var shutdownTimeoutSeconds: Long = 30,

  /**
   * Queues this worker will poll, in priority order.
   * Empty list (the default) polls all queues globally.
   *
   * Example for a node that only handles emails and falls back to default:
   * `queues: ["emails", "default"]`
   */
  var queues: List<String> = emptyList(),

  /**
   * Postgres schema where Kool Queue's tables live.
   * - null/empty (default): tables are created and queried in the connection's
   *   default schema (typically `public`), preserving the legacy behavior.
   * - non-empty: the schema is created if missing and all DDL/DML is qualified
   *   as `<schema>.kool_queue_*`, so Kool Queue's tables stay isolated from
   *   the host application's tables.
   *
   * Must be a valid SQL identifier (`[A-Za-z_][A-Za-z0-9_]*`).
   */
  var schema: String? = null
)
