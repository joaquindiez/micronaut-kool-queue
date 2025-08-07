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

import com.joaquindiez.koolQueue.core.KoolQueueTask
import com.joaquindiez.koolQueue.domain.KoolQueueJobs
import io.micronaut.context.BeanContext
import io.micronaut.json.JsonMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import jakarta.persistence.EntityManagerFactory
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import io.micronaut.context.ApplicationContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream


@Singleton
class KoolQueueScheduledJob(
  private val taskService: KoolQueueJobsService,
  private val jsonMapper: JsonMapper,
  private val applicationContext: ApplicationContext  // ✅ Añadido para verificar shutdown
   ) {

  @Inject
  lateinit var beanContext: BeanContext

  private val logger = LoggerFactory.getLogger(javaClass)

  //@Scheduled(fixedRate = "2s", fixedDelay = "5s")
  @KoolQueueTask(name = "checkKoolTasksTasks", interval = "2s", initialDelay = "10s")
  fun checkPendingTasks() {

    // ✅ VERIFICAR: ¿La aplicación se está cerrando?
    if (!applicationContext.isRunning) {
      logger.debug("Application is shutting down - skipping pending tasks check")
      return
    }

    // ✅ VERIFICAR: ¿La base de datos está disponible?
    if (!isDatabaseAvailable()) {
      logger.debug("Database is not available - skipping pending tasks check")
      return
    }

    val nextPendingTasks =  taskService.findNextJobsPending(limit = 1)
    logger.debug("Check next Jobs to Run pending jobs to Run ${nextPendingTasks.size}")

    for (jobTask in nextPendingTasks) {
      // ✅ VERIFICAR: Estado antes de procesar cada job
      if (!applicationContext.isRunning || !isDatabaseAvailable()) {
        logger.debug("Application/Database shutting down - stopping job processing")
        return
      }
      processJobTaskSafely(jobTask)
    }
  }



  private fun processJobTaskSafely(jobTask: KoolQueueJobs) {
    // Usar reflexión para obtener propiedades del jobTask
    val jobTaskClass = jobTask::class.java
    val classNameField = jobTaskClass.getDeclaredField("className").apply { isAccessible = true }
    val jobIdField = jobTaskClass.getDeclaredField("jobId").apply { isAccessible = true }
    val metadataField = jobTaskClass.getDeclaredField("metadata").apply { isAccessible = true }

    val className = classNameField.get(jobTask) as String
    val jobId = jobIdField.get(jobTask)
    val metadata = metadataField.get(jobTask) as String

    try {
      val applicationJob = beanContext.getBean(Class.forName(className))
      val inputStream = ByteArrayInputStream(metadata.toByteArray())

      if (applicationJob is ApplicationJob<*>?) {
        // Recuperar el tipo de datos
        val dataType = applicationJob.getDataType()
        val koolTaskData = jsonMapper.readValue(inputStream, dataType)

        try {
          val result = applicationJob.processInternal(koolTaskData!!)

          result.fold(
            onSuccess = {
              logger.info("Job taskId=$jobId className=$className finished successfully")

              // ✅ SAFE UPDATE: Verificar antes de actualizar BD
              safeUpdateJobStatus(jobTask, jobId.toString(), "success") {
                taskService.finishSuccessTask(jobTask)
              }
            },
            onFailure = {
              logger.error("Job taskId=$jobId className=$className finished onError")

              safeUpdateJobStatus(jobTask, jobId.toString(), "failure") {
                taskService.finishOnErrorTask(jobTask)
              }
            }
          )

        } catch (ex: Exception) {
          logger.error("Job taskId=$jobId className=$className UnExpected failure", ex)

          safeUpdateJobStatus(jobTask, jobId.toString(), "unexpected error") {
            taskService.finishOnErrorTask(jobTask)
          }
        }

      } else {
        logger.error("Job className=$className not valid taskId=$jobId")

        safeUpdateJobStatus(jobTask, jobId.toString(), "invalid job class") {
          taskService.finishOnErrorTask(jobTask)
        }
      }

    } catch (e: Exception) {
      val jobId = try {
        jobTaskClass.getDeclaredField("jobId").apply { isAccessible = true }.get(jobTask)
      } catch (ex: Exception) { "unknown" }

      logger.error("Error processing job taskId=$jobId className=$className", e)

      safeUpdateJobStatus(jobTask, jobId.toString(), "processing error") {
        taskService.finishOnErrorTask(jobTask)
      }
    }
  }

  // ✅ MÉTODO SEGURO: Solo actualiza BD si está disponible
  private fun safeUpdateJobStatus(jobTask: Any, jobId: String, operation: String, updateAction: () -> Unit) {
    try {
      // Verificar estado antes de intentar actualizar
      if (!applicationContext.isRunning) {
        logger.debug("Skipping job status update for $jobId ($operation) - application shutting down")
        return
      }

      if (!isDatabaseAvailable()) {
        logger.debug("Skipping job status update for $jobId ($operation) - database not available")
        return
      }

      // Ejecutar la actualización
      updateAction()

    } catch (e: Exception) {
      handleShutdownAwareException(e, "job status update for $jobId ($operation)")
    }
  }

  // ✅ VERIFICAR: ¿Está disponible la base de datos?
  private fun isDatabaseAvailable(): Boolean {
    return try {
      val entityManagerFactory = applicationContext.findBean(EntityManagerFactory::class.java)
      entityManagerFactory.isPresent && entityManagerFactory.get().isOpen
    } catch (e: Exception) {
      false
    }
  }

  // ✅ MANEJO INTELIGENTE: Distinguir errores de shutdown vs errores reales
  private fun handleShutdownAwareException(e: Exception, operation: String) {
    when {
      e.message?.contains("EntityManagerFactory is closed") == true -> {
        logger.debug("EntityManagerFactory closed during $operation - application shutting down")
      }
      e.message?.contains("Connection pool closed") == true -> {
        logger.debug("Connection pool closed during $operation - application shutting down")
      }
      e.message?.contains("HikariPool") == true && e.message?.contains("shutdown") == true -> {
        logger.debug("HikariPool shutdown during $operation")
      }
      e.message?.contains("HHH000026: Second-level cache disabled") == true -> {
        logger.debug("Hibernate cache disabled during $operation - likely shutdown")
      }
      !applicationContext.isRunning -> {
        logger.debug("Error during $operation while shutting down: ${e.message}")
      }
      else -> {
        // Es un error real, no de shutdown
        logger.error("Error during $operation", e)
      }
    }
  }


}
