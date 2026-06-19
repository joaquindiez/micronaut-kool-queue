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
 package com.freesoullabs


import com.joaquindiez.koolQueue.jobs.ApplicationJob
import com.joaquindiez.koolQueue.jobs.KoolQueueJob
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger


@KoolQueueJob(queue = "emails")
class TestJobs : ApplicationJob<String>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun process(data: String): Result<Boolean> {
    logger.info("Procesando Test Jobs (queue=$queue) -> $data")
    Thread.sleep(2_000)
    return Result.success(true)
  }

}

@KoolQueueJob(queue = "reports")
class ReportJobs : ApplicationJob<String>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun process(data: String): Result<Boolean> {
    logger.info("Procesando Report (queue=$queue) -> $data")
    Thread.sleep(2_000)
    return Result.success(true)
  }
}

/**
 * Demo job that fails its first two runs and succeeds on the third — proves the
 * retry-with-backoff path recovers a transient failure. The counter lives on
 * the singleton bean, so it persists across the job's retries (single instance).
 */
@KoolQueueJob(queue = "emails", maxAttempts = 5)
class FlakyJob : ApplicationJob<String>() {

  private val logger = LoggerFactory.getLogger(javaClass)
  private val runs = AtomicInteger(0)

  override fun process(data: String): Result<Boolean> {
    val n = runs.incrementAndGet()
    return if (n < 3) {
      logger.warn("FlakyJob run #$n FAILING on purpose -> $data")
      Result.failure(RuntimeException("transient failure #$n"))
    } else {
      logger.info("FlakyJob run #$n SUCCEEDING -> $data")
      Result.success(true)
    }
  }
}

/**
 * Demo job that always fails, capped at 3 attempts — proves the job is retried
 * up to the budget and then dead-lettered to failed_executions.
 */
@KoolQueueJob(queue = "emails", maxAttempts = 3)
class AlwaysFailingJob : ApplicationJob<String>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun process(data: String): Result<Boolean> {
    logger.warn("AlwaysFailingJob FAILING -> $data")
    return Result.failure(RuntimeException("always boom"))
  }
}
