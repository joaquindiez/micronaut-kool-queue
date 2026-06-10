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
// ==========================================
// SOLUCIÓN 1: @PostConstruct (Recomendada)
// ==========================================


import com.joaquindiez.koolQueue.BasicKoolQueueMessageProducer
import com.joaquindiez.koolQueue.domain.JobReference
import jakarta.inject.Inject
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import java.time.LocalDateTime

abstract class ApplicationJob<T : Any> {

  private val logger = LoggerFactory.getLogger(javaClass)

  @Inject
  private lateinit var basicKoolQueueMessageProducer: BasicKoolQueueMessageProducer

  // ✅ Initialized after dependency injection
  private lateinit var cachedDataType: Class<T>

  /**
   * Default queue used when processLater is invoked without an explicit queue.
   * Override in subclasses to route a job class to a dedicated queue:
   * `override val queue: String = "emails"`
   */
  open val queue: String = DEFAULT_QUEUE

  abstract fun process(data: T): Result<Boolean>


  @PostConstruct
  private fun initializeJob() {
    try {
      cachedDataType = detectDataType()
      logger.debug("Initialized ${this::class.simpleName} with data type: ${cachedDataType.simpleName}")
    } catch (e: Exception) {
      logger.error("Failed to initialize data type for ${this::class.simpleName}", e)
      throw IllegalStateException("Cannot initialize job ${this::class.simpleName}", e)
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun detectDataType(): Class<T> {
    // Usar Java reflection que es más confiable
    val superclass = this.javaClass.genericSuperclass

    return when (superclass) {
      is ParameterizedType -> {
        val typeArguments = superclass.actualTypeArguments
        if (typeArguments.isNotEmpty()) {
          typeArguments[0] as Class<T>
        } else {
          throw IllegalStateException("No type arguments found for ${this::class.simpleName}")
        }
      }
      else -> {
        // Buscar en la jerarquía si no se encuentra directamente
        findTypeInHierarchy()
      }
    }
  }

  @Suppress("UNCHECKED_CAST")
  private fun findTypeInHierarchy(): Class<T> {
    var currentClass: Class<*>? = this.javaClass

    while (currentClass != null && currentClass != ApplicationJob::class.java) {
      val genericSuperclass = currentClass.genericSuperclass

      if (genericSuperclass is ParameterizedType &&
        genericSuperclass.rawType == ApplicationJob::class.java) {

        val typeArguments = genericSuperclass.actualTypeArguments
        if (typeArguments.isNotEmpty()) {
          return typeArguments[0] as Class<T>
        }
      }
      currentClass = currentClass.superclass
    }

    throw IllegalStateException("Cannot find ApplicationJob<T> in class hierarchy for ${this::class.simpleName}")
  }

  fun getDataType(): Class<T> {
    if (!::cachedDataType.isInitialized) {
      throw IllegalStateException("Job not properly initialized. This should not happen with Micronaut.")
    }
    return cachedDataType
  }

  /**
   * Enqueues a job for later processing.
   *
   * @param data The payload to be processed by this job
   * @param queue Queue to enqueue into. If null, uses the job class default ([queue]).
   * @param scheduledAt Optional time to schedule the job (null for immediate execution)
   * @return JobReference containing the job ID for tracking status
   *
   * Example usage:
   * ```kotlin
   * val jobRef = myJob.processLater(payload)
   * println("Job enqueued with ID: ${jobRef.jobId}")
   *
   * // Override the destination queue per call:
   * myJob.processLater(payload, queue = "high-priority")
   *
   * // Later, check the status
   * val status = jobTracker.getStatus(jobRef.jobId)
   * ```
   */
  fun processLater(data: T, queue: String? = null, scheduledAt: LocalDateTime? = null): JobReference {
    try {
      val dataType = getDataType()
      val jobType = this::class.java  // ← Pasar la clase del job
      val effectiveQueue = queue ?: this.queue

      logger.debug("Enqueueing job of type: ${dataType.simpleName} on queue '$effectiveQueue'")
      // The reference is returned to the caller, never stored on this bean:
      // these singletons are shared across producer calls (and reused by the
      // worker), so per-call state here would race and could be dereferenced
      // uninitialized on a worker that never produced for this class.
      return basicKoolQueueMessageProducer.send(data, jobType, effectiveQueue, scheduledAt)
    } catch (e: Exception) {
      logger.error("Failed to enqueue job", e)
      throw e
    }
  }


  internal fun processInternal(rawData: Any): Result<Boolean> {
    @Suppress("UNCHECKED_CAST")
    val typedData = rawData as T
    return process(typedData)
  }

  companion object {
    const val DEFAULT_QUEUE: String = "default"
  }
}