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
// Jakarta/Java EE

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy


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
import java.util.concurrent.CancellationException

// Time


class KoolQueueScheduler(
  private val config: KoolQueueSchedulerConfig
) {

  private val logger = LoggerFactory.getLogger(KoolQueueScheduler::class.java)

  private val scope = CoroutineScope(
    SupervisorJob() +
        Dispatchers.IO +
        CoroutineName("HeavyTaskScheduler")
  )

  private val semaphore = Semaphore(config.maxConcurrentTasks)
  private val activeTasks = AtomicInteger(0)
  private val executionStats = ExecutionStats()

  // Registro de tareas registradas por la aplicación
  private val registeredTasks = mutableMapOf<String, RegisteredTask>()

  @PostConstruct
  fun initialize() {
    logger.info("KoolQueueScheduler inicializado - Max concurrent: ${config.maxConcurrentTasks}")
  }

  /**
   * Registra una tarea para ser ejecutada periódicamente
   */
  fun registerTask(
    name: String,
    task: suspend () -> Unit,
    interval: Duration = Duration.parse(config.defaultInterval),
    initialDelay: Duration = Duration.parse(config.defaultInitialDelay)
  ): TaskRegistration {

    val registeredTask = RegisteredTask(name, task, interval, initialDelay)
    registeredTasks[name] = registeredTask

    // Iniciar la ejecución programada
    val scheduledFuture = startPeriodicExecution(registeredTask)

    logger.info("Tarea '$name' registrada - Intervalo: $interval, Delay inicial: $initialDelay")

    return TaskRegistration(name, scheduledFuture, this)
  }

  private fun startPeriodicExecution(task: RegisteredTask): ScheduledFuture<*> {
    return Executors.newScheduledThreadPool(1).scheduleWithFixedDelay({
      executeTask(task)
    }, task.initialDelay.inWholeMilliseconds, task.interval.inWholeMilliseconds, TimeUnit.MILLISECONDS)
  }

  private fun executeTask(task: RegisteredTask) {
    scope.launch {
      if (semaphore.tryAcquire()) {
        val currentActive = activeTasks.incrementAndGet()
        val executionId = executionStats.incrementTotal()

        logger.debug("Ejecutando tarea '${task.name}' #$executionId ($currentActive/${config.maxConcurrentTasks} activas)")

        try {
          task.taskFunction()
          executionStats.incrementSuccess()
          logger.debug("Tarea '${task.name}' #$executionId completada")

        } catch (e: CancellationException) {
          logger.warn("Tarea '${task.name}' #$executionId cancelada")
          throw e

        } catch (e: Exception) {
          executionStats.incrementFailure()
          logger.error("Error en tarea '${task.name}' #$executionId: ${e.message}", e)

        } finally {
          activeTasks.decrementAndGet()
          semaphore.release()
        }
      } else {
        logger.warn("Máximo de tareas concurrentes alcanzado - Saltando ejecución de '${task.name}'")
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

  @PreDestroy
  fun shutdown() {
    logger.info("Iniciando shutdown del HeavyTaskScheduler...")
    runBlocking {
      try {
        scope.cancel()
        withTimeoutOrNull(config.shutdownTimeoutSeconds.seconds) {
          while (activeTasks.get() > 0) {
            delay(100.milliseconds)
          }
          scope.coroutineContext[Job]?.join()
        }
        logger.info("HeavyTaskScheduler cerrado correctamente")
      } catch (e: Exception) {
        logger.error("Error durante shutdown: ${e.message}", e)
      }
    }
  }
}
