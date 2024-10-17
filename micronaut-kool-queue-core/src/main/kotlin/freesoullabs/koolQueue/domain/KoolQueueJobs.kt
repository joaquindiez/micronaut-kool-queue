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
package freesoullabs.koolQueue.domain

import io.micronaut.serde.annotation.Serdeable

import java.time.LocalDateTime
import java.util.*

@Serdeable
data class KoolQueueJobs(

  val id: Long? = null,
  val queueName: String = "default",
  val jobId: UUID,
  val className: String,
  val priority: Int = 0,
  val metadata: String,
  var status: TaskStatus = TaskStatus.PENDING,
  var scheduledAt: LocalDateTime? = null,
  var finishedAt: LocalDateTime? = null,
  var createdAt: LocalDateTime = LocalDateTime.now(),
  var updatedAt: LocalDateTime = LocalDateTime.now()
)
