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

import io.micronaut.context.BeanContext
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.inject.Singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.jvm.kotlinFunction
import kotlin.time.Duration
import kotlinx.coroutines.*


@Singleton
class KoolQueueAnnotationProcessor(
  private val scheduler: KoolQueueScheduler,
  private val beanContext: BeanContext
) : ApplicationEventListener<StartupEvent> {

  private val logger: Logger = LoggerFactory.getLogger(KoolQueueAnnotationProcessor::class.java)

  override fun onApplicationEvent(event: StartupEvent) {
    beanContext.getAllBeanDefinitions()
      .forEach { beanDefinition ->
        try {
          val bean = beanContext.getBean(beanDefinition.beanType)

          // Safely get declared methods, handling NoClassDefFoundError for missing dependencies
          val methods = try {
            beanDefinition.beanType.declaredMethods
          } catch (e: NoClassDefFoundError) {
            logger.debug("Skipping bean ${beanDefinition.beanType.simpleName} due to missing dependencies: ${e.message}")
            return@forEach
          }

          methods
            .filter { it.isAnnotationPresent(KoolQueueTask::class.java) }
            .forEach { method ->
              val annotation = method.getAnnotation(KoolQueueTask::class.java)
              val taskName = annotation.name.ifEmpty {
                "${beanDefinition.beanType.simpleName}.${method.name}"
              }

              // Verificar si es suspend function
              val isSuspend = method.kotlinFunction?.isSuspend == true

              scheduler.registerTask(
                name = taskName,
                task = {
                  try {
                    if (isSuspend) {
                      // Para funciones suspend, necesitamos reflection específica
                      val result = method.invoke(bean)
                      if (result is kotlin.coroutines.Continuation<*>) {
                        // Es una función suspend
                        suspendCoroutine<Unit> { cont: kotlin.coroutines.Continuation<Unit> ->
                          method.invoke(bean, cont)
                        }
                      }
                    } else {
                      // Función normal
                      method.invoke(bean)
                    }
                  } catch (e: Exception) {
                    logger.error("Error ejecutando tarea anotada $taskName", e)
                    throw e
                  }
                },
                interval = Duration.parse(annotation.interval),
                initialDelay = Duration.parse(annotation.initialDelay)
              )

              logger.info("Auto-registrada tarea kool queue: $taskName")
            }
        } catch (e: Exception) {
          logger.debug("Error procesando bean ${beanDefinition.beanType.simpleName}", e)
        }
      }
  }
}