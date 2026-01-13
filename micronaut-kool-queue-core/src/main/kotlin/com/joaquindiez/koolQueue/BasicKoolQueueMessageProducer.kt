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
import com.joaquindiez.koolQueue.domain.JobReference
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import java.time.LocalDateTime
import java.time.ZoneId

@Singleton
class BasicKoolQueueMessageProducer(
  private val taskService: KoolQueueJobsService,
  private val jsonMapper: JsonMapper,
) : KoolQueueMessageProducer {

  private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)

  override fun send(msg: Any, className: Class<*>, scheduledAt: LocalDateTime?): JobReference {
    val json = jsonMapper.writeValueAsString(msg)

    val scheduledTime = scheduledAt ?: LocalDateTime.now()
    val scheduledInstant = scheduledTime.atZone(ZoneId.systemDefault()).toInstant()
    val uuidV7 = UuidCreator.getTimeOrderedEpoch()

    val task = KoolQueueJobs(
      className = className.canonicalName,
      arguments = json,
      scheduledAt = scheduledInstant,
      activeJobId = uuidV7,
    )

    // Insert in job and ready/scheduled executions
    if (scheduledAt == null) {
      val job = taskService.addJobReady(task)
      logger.info("Job enqueued: $job")
    } else {
      val job = taskService.enqueueAsScheduled(task)
      logger.info("Job Scheduled: $job")
    }

    // Return a reference to track the job
    return JobReference(
      jobId = uuidV7,
      className = className.canonicalName,
      scheduledAt = if (scheduledAt != null) scheduledInstant else null
    )
  }
}
