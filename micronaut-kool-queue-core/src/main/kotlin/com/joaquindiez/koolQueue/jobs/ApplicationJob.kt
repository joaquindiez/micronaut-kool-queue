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
import jakarta.inject.Inject
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import java.time.LocalDateTime

abstract class ApplicationJob<T : Any> {

  private val logger = LoggerFactory.getLogger(javaClass)

  @Inject
  private lateinit var basicKoolQueueMessageProducer: BasicKoolQueueMessageProducer

  // ✅ Se inicializa después de la inyección de dependencias
  private lateinit var cachedDataType: Class<T>


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

  fun processLater(data: T, scheduledAt: LocalDateTime? = null) {
    try {
      val dataType = getDataType()
      val jobType = this::class.java  // ← Pasar la clase del job

      logger.debug("Enqueueing job of type: ${dataType.simpleName}")
      basicKoolQueueMessageProducer.send(data, jobType, scheduledAt)
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
}