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
package com.joaquindiez.koolQueue.domain

import io.micronaut.serde.annotation.Serdeable
import java.time.Instant

import java.time.LocalDateTime
import java.util.*

@Serdeable
data class KoolQueueJobs(

  val id: Long? = null,
  val queueName: String = "default",
  val className: String,
  val arguments: String,
  val priority: Int = 0,
  val activeJobId: UUID,
  var scheduledAt: Instant? = null,
  var finishedAt: Instant? = null,
  var concurrency_key: String? = null,
  var createdAt: LocalDateTime = LocalDateTime.now(),
  var updatedAt: LocalDateTime = LocalDateTime.now()
)
