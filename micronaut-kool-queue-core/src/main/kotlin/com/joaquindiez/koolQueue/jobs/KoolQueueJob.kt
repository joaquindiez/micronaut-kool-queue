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
package com.joaquindiez.koolQueue.jobs

import jakarta.inject.Singleton

/**
 * Declares a job class and, optionally, the queue it routes to.
 *
 * Meta-annotated with [Singleton]: a class carrying `@KoolQueueJob` is
 * registered as a singleton bean automatically, so you no longer need a
 * separate `@Singleton`. The class must still extend [ApplicationJob] and
 * implement `process`.
 *
 * ```kotlin
 * @KoolQueueJob(queue = "reports")
 * class ReportJobs : ApplicationJob<String>() {
 *   override fun process(data: String): Result<Boolean> = ...
 * }
 * ```
 *
 * Queue resolution precedence, highest first:
 *  1. the `queue` argument passed to `processLater(...)`
 *  2. an `override val queue` on the job class (use for dynamic/computed queues)
 *  3. this annotation's [queue]
 *  4. [ApplicationJob.DEFAULT_QUEUE]
 *
 * [maxAttempts] overrides the global `micronaut.scheduler.kool-queue.max-attempts`
 * for this job class. Leave at the default (`-1`) to inherit the global value.
 */
@Singleton
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KoolQueueJob(
  val queue: String = ApplicationJob.DEFAULT_QUEUE,
  val maxAttempts: Int = -1
)