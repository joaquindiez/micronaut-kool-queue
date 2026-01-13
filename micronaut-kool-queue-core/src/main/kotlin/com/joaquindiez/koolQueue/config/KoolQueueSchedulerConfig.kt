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
  var shutdownTimeoutSeconds: Long = 30
)
