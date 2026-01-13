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
import java.util.UUID

/**
 * A lightweight reference to a queued job.
 * This is returned when a job is enqueued via processLater().
 *
 * Use this reference to:
 * - Track the job ID for later status queries
 * - Store the job ID in your domain entities for correlation
 * - Pass to KoolQueueJobTracker to get the current status
 *
 * Example usage:
 * ```kotlin
 * val jobRef = myJob.processLater(payload)
 * order.emailJobId = jobRef.jobId
 * orderRepository.save(order)
 *
 * // Later...
 * val status = jobTracker.getStatus(order.emailJobId)
 * ```
 */
@Serdeable
data class JobReference(
    /**
     * The unique job identifier (UUID v7, time-ordered).
     * This ID can be used to query the job status at any time.
     */
    val jobId: UUID,

    /**
     * Fully qualified class name of the job that will process this work.
     */
    val className: String,

    /**
     * When the job is scheduled to run.
     * Null if the job was enqueued for immediate execution.
     */
    val scheduledAt: Instant?
) {
    /**
     * Returns the simple class name without package prefix.
     */
    fun getSimpleClassName(): String = className.substringAfterLast('.')

    /**
     * Returns true if this job was scheduled for a future time.
     */
    fun isScheduled(): Boolean = scheduledAt != null

    override fun toString(): String =
        "JobReference(jobId=$jobId, class=${getSimpleClassName()}, scheduled=${isScheduled()})"
}