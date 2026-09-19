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

import com.joaquindiez.koolQueue.config.KoolQueueSchedulerConfig
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fixed-size pool that actually runs claimed jobs, kept separate from the
 * scheduler's own polling threads.
 *
 * The point of the separation is [availableCapacity]: the ready poller asks how
 * many jobs it can still take on and claims exactly that many, instead of a
 * constant. That keeps the claim rate tied to real execution capacity — claim
 * too few and the pool starves, claim too many and jobs sit reserved by a
 * worker that cannot get to them (and would have to be recovered by the reaper
 * if it died).
 *
 * Capacity is reserved at submission, not when the job starts running, so two
 * polls in quick succession cannot both count the same free slot.
 */
@Singleton
open class KoolQueueJobExecutionPool(
  config: KoolQueueSchedulerConfig
) {

  private val logger = LoggerFactory.getLogger(javaClass)

  /** Number of jobs that may run at once. Always at least one. */
  val size: Int = config.jobExecutionThreads.coerceAtLeast(1)

  private val shutdownTimeoutSeconds: Long = config.shutdownTimeoutSeconds

  private val inFlight = AtomicInteger(0)

  private val idleListeners = CopyOnWriteArrayList<() -> Unit>()

  private val executor: ThreadPoolExecutor = Executors.newFixedThreadPool(size) { runnable ->
    Thread(runnable, "kool-queue-job-executor").apply { isDaemon = true }
  } as ThreadPoolExecutor

  /** Free slots right now: what the poller should use as its claim batch size. */
  val availableCapacity: Int
    get() = (size - inFlight.get()).coerceAtLeast(0)

  /** Jobs currently submitted but not finished. */
  val inFlightCount: Int
    get() = inFlight.get()

  /**
   * Submits [work] for execution, reserving a slot up front.
   *
   * Returns false when the pool is shutting down and the job could not be
   * accepted; the caller is responsible for leaving such a job claimed so the
   * reaper can release it.
   */
  fun post(work: () -> Unit): Boolean {
    inFlight.incrementAndGet()
    return try {
      executor.execute {
        try {
          work()
        } finally {
          inFlight.decrementAndGet()
          notifyIdle()
        }
      }
      true
    } catch (e: RejectedExecutionException) {
      inFlight.decrementAndGet()
      logger.warn("Job execution pool rejected a job (shutting down?)", e)
      false
    }
  }

  /**
   * Registers a callback fired whenever a job finishes and a slot frees up.
   *
   * The poller uses this to claim again immediately instead of waiting out the
   * rest of its interval, which is what lets throughput track how fast jobs
   * complete rather than the polling clock.
   */
  fun onIdle(listener: () -> Unit) {
    idleListeners.add(listener)
  }

  private fun notifyIdle() {
    // Runs on the worker thread that just finished a job. Listeners must be
    // cheap and must not throw, or they would take the pool thread down with
    // them — hence the catch.
    idleListeners.forEach { listener ->
      try {
        listener()
      } catch (e: Exception) {
        logger.warn("Idle listener failed", e)
      }
    }
  }

  @PreDestroy
  open fun shutdown() {
    logger.debug("Shutting down job execution pool (in-flight={})", inFlight.get())
    executor.shutdown()
    try {
      if (!executor.awaitTermination(shutdownTimeoutSeconds, TimeUnit.SECONDS)) {
        logger.warn(
          "Job execution pool did not drain within {}s; {} job(s) still in flight",
          shutdownTimeoutSeconds,
          inFlight.get()
        )
        executor.shutdownNow()
      }
    } catch (e: InterruptedException) {
      executor.shutdownNow()
      Thread.currentThread().interrupt()
    }
  }
}
