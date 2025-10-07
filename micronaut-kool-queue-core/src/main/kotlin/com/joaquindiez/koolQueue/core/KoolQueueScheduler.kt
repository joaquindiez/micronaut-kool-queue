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

  // Registro de tareas registradas por la aplicación
  private val registeredTasks = mutableMapOf<String, RegisteredTask>()
  private val scheduledFutures = mutableMapOf<String, ScheduledFuture<*>>()

  // ✅ Flag para indicar shutdown interno
  @Volatile
  private var isShuttingDown = false

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

    if (isShuttingDown) {
      logger.warn("Cannot register task '$name' - scheduler is shutting down")
      throw IllegalStateException("Scheduler is shutting down")
    }

    val registeredTask = RegisteredTask(name, task, interval, initialDelay)
    registeredTasks[name] = registeredTask

    // Iniciar la ejecución programada
    val scheduledFuture = startPeriodicExecution(registeredTask)
    scheduledFutures[name] = scheduledFuture

    logger.info("Tarea '$name' registrada - Intervalo: $interval, Delay inicial: $initialDelay")

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
    // ✅ VERIFICAR: Estado antes de ejecutar
    if (isShuttingDown || !applicationContext.isRunning) {
      logger.debug("Skipping task '${task.name}' - scheduler shutting down")
      return
    }

    scope.launch {
      if (semaphore.tryAcquire()) {
        val currentActive = activeTasks.incrementAndGet()
        val executionId = executionStats.incrementTotal()

        logger.debug("Ejecutando tarea '${task.name}' #$executionId ($currentActive/${config.maxConcurrentTasks} activas)")

        try {
          // ✅ VERIFICAR: Estado durante ejecución
          if (isShuttingDown || !applicationContext.isRunning) {
            logger.debug("Task '${task.name}' cancelled - shutdown in progress")
            return@launch
          }

          task.taskFunction()
          executionStats.incrementSuccess()
          logger.debug("Tarea '${task.name}' #$executionId completada")

        } catch (e: CancellationException) {
          logger.warn("Tarea '${task.name}' #$executionId cancelada")
          throw e

        } catch (e: Exception) {
          // ✅ DISTINGUIR: Errores reales vs errores de shutdown
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
              logger.error("Error en tarea '${task.name}' #$executionId: ${e.message}", e)
            }
          }

        } finally {
          val remaining = activeTasks.decrementAndGet()
          semaphore.release()
          logger.debug("Tarea '${task.name}' #$executionId finalizada ($remaining activas restantes)")

        }
      } else {
        logger.debug("Máximo de tareas concurrentes alcanzado - Saltando ejecución de '${task.name}'")
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

      // Crear nuevo semáforo con el nuevo límite
      val newSemaphore = Semaphore(newMax)

      // Si aumentamos el límite, liberar permisos adicionales
      if (newMax > oldMax) {
        val additionalPermits = newMax - oldMax
        newSemaphore.release(additionalPermits)
      }

      logger.info("Límite de tareas concurrentes actualizado: $oldMax -> $newMax")
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
    logger.info("Iniciando shutdown del KoolQueueScheduler...")

    // ✅ MARCAR: Shutdown en progreso ANTES de cancelar tareas
    isShuttingDown = true

    // Cancelar todos los ScheduledFuture
    scheduledFutures.values.forEach { future ->
      try {
        future.cancel(false)
      } catch (e: Exception) {
        logger.debug("Error cancelling scheduled future: ${e.message}")
      }
    }

    runBlocking {
      try {
        // Dar tiempo a que las tareas actuales vean el flag
        delay(100.milliseconds)

        // Cancelar el scope
        scope.cancel()

        // Esperar a que terminen las tareas con timeout más corto
        val shutdownSuccessful = withTimeoutOrNull(config.shutdownTimeoutSeconds.seconds) {
          while (activeTasks.get() > 0) {
            delay(100.milliseconds)
            logger.debug("Esperando ${activeTasks.get()} tareas activas...")
          }
          scope.coroutineContext[Job]?.join()
          true
        } ?: false

        if (shutdownSuccessful) {
          logger.info("KoolQueueScheduler cerrado correctamente")
        } else {
          logger.warn("KoolQueueScheduler shutdown timeout - ${activeTasks.get()} tareas pueden seguir corriendo")
        }

        val finalStats = getStats()
        logger.info("Estadísticas finales del scheduler: $finalStats")

      } catch (e: Exception) {
        // Durante shutdown, muchos errores son normales
        logger.debug("Error durante shutdown (puede ser normal): ${e.message}")
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
