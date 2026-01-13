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
import com.joaquindiez.koolQueue.domain.KoolQueueProcesses
import com.joaquindiez.koolQueue.repository.ProcessesRepository
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Requires
import io.micronaut.core.annotation.Order
// Jakarta/Java EE

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton


// Coroutines
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

// Java Concurrent
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

// Logging
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

// Time


@Singleton
@Order(100)
@Requires(property = "micronaut.scheduler.kool-queue.enabled", value = "true", defaultValue = "true")
class KoolQueueScheduler(
  private val config: KoolQueueSchedulerConfig,
  private val applicationContext: ApplicationContext,
  private val processesRepository: ProcessesRepository
) {

  private val logger = LoggerFactory.getLogger(KoolQueueScheduler::class.java)

  private val scope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineName("KoolQueueScheduler")
  )

  private val semaphore = Semaphore(config.maxConcurrentTasks)
  private val activeTasks = AtomicInteger(0)
  private val executionStats = ExecutionStats()

  // Registry of tasks registered by the application
  private val registeredTasks = mutableMapOf<String, RegisteredTask>()
  private val scheduledFutures = mutableMapOf<String, ScheduledFuture<*>>()

  // ✅ Flag to indicate internal shutdown
  @Volatile
  private var isShuttingDown = false

  @PostConstruct
  fun initialize() {
    logger.info("KoolQueueScheduler initialized - Max concurrent: ${config.maxConcurrentTasks}")
  }

  /**
   * Registers a task to be executed periodically
   */
  fun registerTask(
    name: String,
    task: suspend () -> Unit,
    interval: Duration = Duration.parse(config.defaultInterval),
    initialDelay: Duration = Duration.parse(config.defaultInitialDelay),
    maxConcurrency: Int
  ): TaskRegistration {

    if (isShuttingDown) {
      logger.warn("Cannot register task '$name' - scheduler is shutting down")
      throw IllegalStateException("Scheduler is shutting down")
    }

    val registeredTask = RegisteredTask(name, task, interval, initialDelay, maxConcurrency)
    registeredTasks[name] = registeredTask

    // Start scheduled execution
    val scheduledFuture = startPeriodicExecution(registeredTask)
    scheduledFutures[name] = scheduledFuture

    logger.info("Task '$name' registered - Interval: $interval, Initial delay: $initialDelay, Max concurrency: $maxConcurrency")

    return TaskRegistration(name, scheduledFuture, this)
  }

  private fun startPeriodicExecution(task: RegisteredTask): ScheduledFuture<*> {
    val executor = Executors.newScheduledThreadPool(1) { r ->
      task.currentProcessId = registerCurrentProcess(
        kind = task.name,
        name = "${task.name}-${InetAddress.getLocalHost().hostName}"
      )
      doHeartbeat( task)
      Thread(r, "kool-queue-scheduler-${task.name}")
    }

    return executor.scheduleWithFixedDelay({
      executeTask(task)
    }, task.initialDelay.inWholeMilliseconds, task.interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  }

  private fun executeTask(task: RegisteredTask) {

    doHeartbeat( task)
    // ✅ CHECK: State before executing
    if (isShuttingDown || !applicationContext.isRunning) {
      logger.debug("Skipping task '${task.name}' - scheduler shutting down")
      return
    }

    scope.launch {
      // ✅ Try to acquire GLOBAL semaphore
      if (semaphore.tryAcquire()) {
        try {
          // ✅ Try to acquire TASK semaphore
          if (task.semaphore.tryAcquire()) {
            try {
              val currentActive = activeTasks.incrementAndGet()
              val taskActive = task.activeExecutions.incrementAndGet()
              val executionId = executionStats.incrementTotal()

              logger.debug(
                "Executing task '${task.name}' #$executionId " +
                "(Global: $currentActive/${config.maxConcurrentTasks}, " +
                "Task: $taskActive/${task.maxConcurrency})"
              )

              try {
                // ✅ CHECK: State during execution
                if (isShuttingDown || !applicationContext.isRunning) {
                  logger.debug("Task '${task.name}' cancelled - shutdown in progress")
                  return@launch
                }

                task.taskFunction()
                executionStats.incrementSuccess()
                logger.debug("Task '${task.name}' #$executionId completed")


              } catch (e: CancellationException) {
                logger.warn("Task '${task.name}' #$executionId cancelled")
                throw e

              } catch (e: Exception) {
                // ✅ DISTINGUISH: Real errors vs shutdown errors
                when {
                  e.message?.contains("EntityManagerFactory is closed") == true -> {
                    logger.debug("Task '${task.name}' - EntityManagerFactory closed (shutdown)")
                  }
                  e.message?.contains("Connection pool closed") == true -> {
                    logger.debug("Task '${task.name}' - Connection pool closed (shutdown)")
                  }
                  e.message?.contains("HikariPool") == true && e.message?.contains("shutdown") == true -> {
                    logger.debug("Task '${task.name}' - HikariPool shutdown")
                  }
                  isShuttingDown || !applicationContext.isRunning -> {
                    logger.debug("Task '${task.name}' failed during shutdown: ${e.message}")
                  }
                  else -> {
                    executionStats.incrementFailure()
                    logger.error("Error in task '${task.name}' #$executionId: ${e.message}", e)
                  }
                }

              } finally {
                val remaining = activeTasks.decrementAndGet()
                val taskRemaining = task.activeExecutions.decrementAndGet()
                logger.debug(
                  "Task '${task.name}' #$executionId finished " +
                  "(Global: $remaining, Task: $taskRemaining)"
                )
              }

            } finally {
              // ✅ Release task semaphore
              task.semaphore.release()
            }

          } else {
            logger.debug(
              "Maximum task concurrency '${task.name}' reached " +
              "(${task.activeExecutions.get()}/${task.maxConcurrency}) - Skipping execution"
            )
          }

        } finally {
          // ✅ Release global semaphore
          semaphore.release()
        }

      } else {
        logger.debug("Maximum global concurrent tasks reached - Skipping execution of '${task.name}'")
      }
    }
  }

  fun getStats(): Map<String, Any> {
    return mapOf(
      "activeTasks" to activeTasks.get(),
      "maxConcurrentTasks" to config.maxConcurrentTasks,
      "registeredTasks" to registeredTasks.keys,
      "totalExecutions" to executionStats.total.get(),
      "successfulExecutions" to executionStats.successful.get(),
      "failedExecutions" to executionStats.failed.get(),
      "successRate" to executionStats.getSuccessRate()
    )
  }

  fun getRegisteredTaskNames(): List<String> {
    return registeredTasks.keys.toList()
  }

  fun getActiveTaskCount(): Int {
    return activeTasks.get()
  }

  fun getMaxConcurrentTasks(): Int {
    return config.maxConcurrentTasks
  }

  fun cancelTask(taskName: String): Boolean {
    return try {
      val future = scheduledFutures[taskName]
      val cancelled = future?.cancel(false) ?: false
      if (cancelled) {
        registeredTasks.remove(taskName)
        scheduledFutures.remove(taskName)
        logger.info("Task '$taskName' cancelled successfully")
      }
      cancelled
    } catch (e: Exception) {
      logger.error("Error cancelling task '$taskName'", e)
      false
    }
  }

  fun updateMaxConcurrentTasks(newMax: Int) {
    if (newMax > 0) {
      val oldMax = config.maxConcurrentTasks
      config.maxConcurrentTasks = newMax

      // Create new semaphore with the new limit
      val newSemaphore = Semaphore(newMax)

      // If we increase the limit, release additional permits
      if (newMax > oldMax) {
        val additionalPermits = newMax - oldMax
        newSemaphore.release(additionalPermits)
      }

      logger.info("Concurrent tasks limit updated: $oldMax -> $newMax")
    }
  }

  fun getCurrentConfig(): Map<String, Any> {
    return mapOf(
      "enabled" to config.enabled,
      "maxConcurrentTasks" to config.maxConcurrentTasks,
      "defaultInterval" to config.defaultInterval,
      "defaultInitialDelay" to config.defaultInitialDelay,
      "shutdownTimeoutSeconds" to config.shutdownTimeoutSeconds
    )
  }

  @PreDestroy
  fun shutdown() {
    logger.info("Starting KoolQueueScheduler shutdown...")

    // ✅ MARK: Shutdown in progress BEFORE cancelling tasks
    isShuttingDown = true

    // Cancel all ScheduledFutures
    scheduledFutures.values.forEach { future ->
      try {
        future.cancel(false)
      } catch (e: Exception) {
        logger.debug("Error cancelling scheduled future: ${e.message}")
      }
    }

    runBlocking {
      try {
        // Give time for current tasks to see the flag
        delay(100.milliseconds)

        // Cancel the scope
        scope.cancel()

        // Wait for tasks to finish with shorter timeout
        val shutdownSuccessful = withTimeoutOrNull(config.shutdownTimeoutSeconds.seconds) {
          while (activeTasks.get() > 0) {
            delay(100.milliseconds)
            logger.debug("Waiting for ${activeTasks.get()} active tasks...")
          }
          scope.coroutineContext[Job]?.join()
          true
        } ?: false

        if (shutdownSuccessful) {
          logger.info("KoolQueueScheduler closed successfully")
        } else {
          logger.warn("KoolQueueScheduler shutdown timeout - ${activeTasks.get()} tasks may still be running")
        }

        val finalStats = getStats()
        logger.info("Final scheduler statistics: $finalStats")

      } catch (e: Exception) {
        // During shutdown, many errors are normal
        logger.debug("Error during shutdown (may be normal): ${e.message}")
      }
    }
  }

  private fun registerCurrentProcess(kind: String, name: String): Long {
    val process = KoolQueueProcesses(
      kind = kind,
      name = name,
      pid = ProcessHandle.current().pid().toInt(),
      hostname = InetAddress.getLocalHost().hostName,
      lastHeartbeatAt = Instant.now()
    )

    val registered = processesRepository.registerProcess(process)
    logger.info("Proceso registrado con ID: ${registered.id}")
    return registered.id!!
  }

  private fun doHeartbeat(task : RegisteredTask) {
    if ( task.lastHeartbeat.plusSeconds(10).isBefore(Instant.now()))  {
      processesRepository.updateHeartbeat(task.currentProcessId)
      task.lastHeartbeat = Instant.now()
    }

  }



}
