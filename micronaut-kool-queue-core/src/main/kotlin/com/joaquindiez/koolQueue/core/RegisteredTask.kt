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
package com.joaquindiez.koolQueue.core

import java.time.Instant
import kotlin.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class RegisteredTask(
  val name: String,
  val taskFunction: suspend () -> Unit,
  val interval: Duration,
  val initialDelay: Duration,
  val maxConcurrency: Int
){
  var currentProcessId : Long = 0
  var lastHeartbeat: Instant = Instant.now()

  // Individual semaphore for this task
  val semaphore = Semaphore(maxConcurrency)
  val activeExecutions = AtomicInteger(0)

  /**
   * Set when something asks this task to run ahead of its next scheduled tick.
   *
   * It is a latch rather than a direct call so a wake-up is never lost: if it
   * arrives while the task is already running (or while concurrency limits are
   * saturated) the flag survives, and whoever finishes re-runs the task instead
   * of leaving the request to expire. Same idea as the self-pipe Solid Queue
   * writes to — the byte stays in the pipe until the sleep reads it.
   */
  val wakeUpRequested = AtomicBoolean(false)
}

class TaskRegistration(
  val name: String,
  private val scheduledFuture: ScheduledFuture<*>,
  private val scheduler: KoolQueueScheduler
) {
  fun cancel() {
    scheduledFuture.cancel(false)
  }

  fun getStats() = scheduler.getStats()
}

class ExecutionStats {
  val total = AtomicLong(0)
  val successful = AtomicLong(0)
  val failed = AtomicLong(0)

  fun incrementTotal(): Long = total.incrementAndGet()
  fun incrementSuccess(): Long = successful.incrementAndGet()
  fun incrementFailure(): Long = failed.incrementAndGet()

  fun getSuccessRate(): Double {
    val totalCount = total.get()
    return if (totalCount > 0) {
      (successful.get().toDouble() / totalCount) * 100
    } else 0.0
  }
}