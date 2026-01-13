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
package com.joaquindiez.koolQueue.services

import com.joaquindiez.koolQueue.domain.JobStatus
import com.joaquindiez.koolQueue.domain.JobStatusInfo
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import com.joaquindiez.koolQueue.repository.FailedExecutionsRepository
import com.joaquindiez.koolQueue.repository.JobsRepository
import com.joaquindiez.koolQueue.repository.KoolQueueClaimedExecutionsRepository
import com.joaquindiez.koolQueue.repository.ReadyExecutionRepository
import com.joaquindiez.koolQueue.repository.ScheduledExecutionRepository
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Service for tracking and querying job status.
 *
 * This service allows applications to check the current status of jobs
 * that were enqueued via processLater().
 *
 * Example usage:
 * ```kotlin
 * @Singleton
 * class OrderService(
 *     private val emailJob: SendEmailJob,
 *     private val jobTracker: KoolQueueJobTracker
 * ) {
 *     fun processOrder(order: Order): JobReference {
 *         val jobRef = emailJob.processLater(EmailPayload(order.email))
 *         order.emailJobId = jobRef.jobId
 *         return jobRef
 *     }
 *
 *     fun checkEmailStatus(jobId: UUID): JobStatus? {
 *         return jobTracker.getStatus(jobId)?.status
 *     }
 * }
 * ```
 */
@Singleton
open class KoolQueueJobTracker(
    private val jobsRepository: JobsRepository,
    private val readyExecutionRepository: ReadyExecutionRepository,
    private val claimedExecutionsRepository: KoolQueueClaimedExecutionsRepository,
    private val scheduledExecutionRepository: ScheduledExecutionRepository,
    private val failedExecutionsRepository: FailedExecutionsRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Gets the current status of a job by its UUID.
     *
     * @param jobId The UUID (activeJobId) of the job to query
     * @return JobStatusInfo with current status, or null if job not found
     */
    @Transactional
    open fun getStatus(jobId: UUID): JobStatusInfo? {
        logger.debug("Getting status for job: $jobId")

        val job = jobsRepository.findByActiveJobId(jobId)
        if (job == null) {
            logger.debug("Job not found: $jobId")
            return null
        }

        return buildStatusInfo(job)
    }

    /**
     * Gets only the status enum for a job (lightweight query).
     *
     * @param jobId The UUID of the job
     * @return JobStatus enum or NOT_FOUND if job doesn't exist
     */
    @Transactional
    open fun getStatusOnly(jobId: UUID): JobStatus {
        val job = jobsRepository.findByActiveJobId(jobId) ?: return JobStatus.NOT_FOUND
        return determineStatus(job)
    }

    /**
     * Checks if a job exists in the system.
     *
     * @param jobId The UUID of the job
     * @return true if the job exists, false otherwise
     */
    @Transactional
    open fun exists(jobId: UUID): Boolean {
        return jobsRepository.findByActiveJobId(jobId) != null
    }

    /**
     * Checks if a job has completed (successfully or with failure).
     *
     * @param jobId The UUID of the job
     * @return true if the job is in a terminal state, false otherwise
     */
    @Transactional
    open fun isComplete(jobId: UUID): Boolean {
        val status = getStatusOnly(jobId)
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED
    }

    /**
     * Waits for a job to complete with a timeout.
     * Uses polling with exponential backoff.
     *
     * @param jobId The UUID of the job to wait for
     * @param timeout Maximum time to wait
     * @param pollInterval Initial polling interval (default 100ms)
     * @return JobStatusInfo when complete, or current status if timeout reached
     */
    @Transactional
    open fun awaitCompletion(
        jobId: UUID,
        timeout: Duration,
        pollInterval: Duration = Duration.ofMillis(100)
    ): JobStatusInfo? {
        val startTime = System.currentTimeMillis()
        val timeoutMillis = timeout.toMillis()
        var currentInterval = pollInterval.toMillis()
        val maxInterval = 2000L // Cap at 2 seconds

        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            val status = getStatus(jobId)

            if (status == null) {
                logger.warn("Job $jobId not found while awaiting completion")
                return null
            }

            if (status.isTerminal()) {
                logger.debug("Job $jobId completed with status: ${status.status}")
                return status
            }

            // Sleep with exponential backoff
            Thread.sleep(currentInterval)
            currentInterval = minOf(currentInterval * 2, maxInterval)
        }

        // Timeout reached, return current status
        logger.debug("Timeout reached waiting for job $jobId")
        return getStatus(jobId)
    }

    /**
     * Gets the error message for a failed job.
     *
     * @param jobId The UUID of the job
     * @return Error message if job failed, null otherwise
     */
    @Transactional
    open fun getErrorMessage(jobId: UUID): String? {
        val job = jobsRepository.findByActiveJobId(jobId) ?: return null
        val failedExecution = failedExecutionsRepository.findByJobId(job.id!!)
        return failedExecution?.error
    }

    /**
     * Builds a complete JobStatusInfo from a KoolQueueJobs entity.
     */
    private fun buildStatusInfo(job: KoolQueueJobs): JobStatusInfo {
        val status = determineStatus(job)
        val errorMessage = if (status == JobStatus.FAILED) {
            failedExecutionsRepository.findByJobId(job.id!!)?.error
        } else {
            null
        }

        return JobStatusInfo(
            jobId = job.activeJobId,
            status = status,
            className = job.className,
            queueName = job.queueName,
            priority = job.priority,
            createdAt = job.createdAt,
            scheduledAt = job.scheduledAt,
            finishedAt = job.finishedAt,
            errorMessage = errorMessage
        )
    }

    /**
     * Determines the current status of a job by checking the execution tables.
     *
     * Status determination logic:
     * 1. If finishedAt is set AND failed_executions exists -> FAILED
     * 2. If finishedAt is set AND no failure -> COMPLETED
     * 3. If in claimed_executions -> IN_PROGRESS
     * 4. If in scheduled_executions -> SCHEDULED
     * 5. If in ready_executions -> PENDING
     * 6. Default -> PENDING (job exists but not in any execution table yet)
     */
    private fun determineStatus(job: KoolQueueJobs): JobStatus {
        val jobId = job.id ?: return JobStatus.PENDING

        return when {
            // Check if job has finished
            job.finishedAt != null -> {
                if (failedExecutionsRepository.existsByJobId(jobId)) {
                    JobStatus.FAILED
                } else {
                    JobStatus.COMPLETED
                }
            }
            // Check if job is currently being executed
            claimedExecutionsRepository.existsByJobId(jobId) -> JobStatus.IN_PROGRESS
            // Check if job is scheduled for later
            scheduledExecutionRepository.existsByJobId(jobId) -> JobStatus.SCHEDULED
            // Check if job is ready for execution
            readyExecutionRepository.existsByJobId(jobId) -> JobStatus.PENDING
            // Default case - job exists but not in any queue yet
            else -> JobStatus.PENDING
        }
    }
}
