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

import com.github.f4b6a3.uuid.UuidCreator
import io.micronaut.json.JsonMapper
import jakarta.inject.Singleton
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.domain.TaskStatus
import com.joaquindiez.koolQueue.services.KoolQueueJobsService

@Singleton
class BasicKoolQueueMessageProducer(
  private val taskService: KoolQueueJobsService,
  private val jsonMapper: JsonMapper,
) : KoolQueueMessageProducer {

  override fun send(msg: Any, className: Class<*>) {
    val json = jsonMapper.writeValueAsString(msg)

    val uuidV7 = UuidCreator.getTimeOrderedEpoch()
    val task = KoolQueueJobs(
      jobId = uuidV7,
      className = className.canonicalName,
      status = TaskStatus.PENDING,
      metadata = json
    )

    taskService.addTask(task)
  }
}
