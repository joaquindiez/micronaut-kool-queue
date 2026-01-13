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

/**
 * Represents the current status of a job in the queue system.
 */
@Serdeable
enum class JobStatus {
    /**
     * Job is in ready_executions, waiting for a worker to pick it up.
     */
    PENDING,

    /**
     * Job is in scheduled_executions, waiting for its scheduled time.
     */
    SCHEDULED,

    /**
     * Job is in claimed_executions, currently being executed by a worker.
     */
    IN_PROGRESS,

    /**
     * Job has finished_at set and no error record exists.
     */
    COMPLETED,

    /**
     * Job has an entry in failed_executions.
     */
    FAILED,

    /**
     * Job was not found in the system.
     */
    NOT_FOUND
}
