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
import java.util.UUID

/**
 * Contains detailed status information about a job.
 * This is returned when querying the status of a job by its ID.
 */
@Serdeable
data class JobStatusInfo(
    /**
     * The unique job identifier (UUID v7).
     */
    val jobId: UUID,

    /**
     * Current status of the job.
     */
    val status: JobStatus,

    /**
     * Fully qualified class name of the job.
     */
    val className: String,

    /**
     * Queue name where the job was enqueued.
     */
    val queueName: String,

    /**
     * Priority of the job (lower is higher priority).
     */
    val priority: Int,

    /**
     * When the job was created.
     */
    val createdAt: LocalDateTime,

    /**
     * When the job is/was scheduled to run (null for immediate jobs).
     */
    val scheduledAt: Instant?,

    /**
     * When the job finished execution (null if not finished).
     */
    val finishedAt: Instant?,

    /**
     * Error message if the job failed (null otherwise).
     */
    val errorMessage: String? = null
) {
    /**
     * Returns true if the job has completed (either successfully or with failure).
     */
    fun isTerminal(): Boolean = status == JobStatus.COMPLETED || status == JobStatus.FAILED

    /**
     * Returns true if the job completed successfully.
     */
    fun isSuccessful(): Boolean = status == JobStatus.COMPLETED

    /**
     * Returns true if the job failed.
     */
    fun isFailed(): Boolean = status == JobStatus.FAILED

    /**
     * Returns true if the job is still running or pending.
     */
    fun isActive(): Boolean = status == JobStatus.PENDING ||
                              status == JobStatus.SCHEDULED ||
                              status == JobStatus.IN_PROGRESS
}