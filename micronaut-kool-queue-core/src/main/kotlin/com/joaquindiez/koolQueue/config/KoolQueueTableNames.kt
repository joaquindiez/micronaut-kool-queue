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

import jakarta.inject.Singleton

/**
 * Centralizes the fully-qualified table names used by every repository.
 *
 * When `schema` is set on [KoolQueueSchedulerConfig], names come back as
 * `<schema>.kool_queue_jobs`; otherwise they are bare names that resolve
 * against the connection's default schema (`public`).
 *
 * The schema name is validated against a strict identifier regex on
 * construction because it is interpolated directly into SQL — there is no
 * way to bind a schema or table identifier as a JDBC parameter.
 */
@Singleton
class KoolQueueTableNames(config: KoolQueueSchedulerConfig) {

  /** Configured schema, or null if tables live in the default schema. */
  val schema: String? = config.schema?.takeIf { it.isNotBlank() }

  init {
    schema?.let {
      require(VALID_IDENTIFIER.matches(it)) {
        "Invalid kool-queue schema name '$it' — must match ${VALID_IDENTIFIER.pattern}"
      }
    }
  }

  /** What to query in `information_schema.tables.table_schema`. */
  val informationSchemaName: String = schema ?: "public"

  val jobs: String = qualify("kool_queue_jobs")
  val readyExecutions: String = qualify("kool_queue_ready_executions")
  val scheduledExecutions: String = qualify("kool_queue_scheduled_executions")
  val claimedExecutions: String = qualify("kool_queue_claimed_executions")
  val failedExecutions: String = qualify("kool_queue_failed_executions")
  val processes: String = qualify("kool_queue_processes")
  val recurringExecutions: String = qualify("kool_queue_recurring_executions")
  val blockedExecutions: String = qualify("kool_queue_blocked_executions")
  val semaphores: String = qualify("kool_queue_semaphores")
  val pauses: String = qualify("kool_queue_pauses")

  /** All bare (unqualified) table names, in dependency-safe drop order. */
  val allBareNamesDropOrder: List<String> = listOf(
    "kool_queue_pauses",
    "kool_queue_semaphores",
    "kool_queue_blocked_executions",
    "kool_queue_recurring_executions",
    "kool_queue_processes",
    "kool_queue_failed_executions",
    "kool_queue_claimed_executions",
    "kool_queue_scheduled_executions",
    "kool_queue_ready_executions",
    "kool_queue_jobs"
  )

  /** All qualified table names, in dependency-safe drop order. */
  val allQualifiedDropOrder: List<String> = allBareNamesDropOrder.map(::qualify)

  private fun qualify(name: String): String =
    if (schema == null) name else "$schema.$name"

  companion object {
    private val VALID_IDENTIFIER = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
  }
}
