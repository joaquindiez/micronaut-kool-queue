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
    beanContext.getAllBeanDefinitions().forEach { beanDefinition ->
      processBean(beanDefinition.beanType)
    }
  }

  private fun processBean(beanType: Class<*>) {
    // Step 1: Cheap reflection — does this class declare any @KoolQueueTask method?
    //
    // We iterate declaredMethods on every bean in the context, including beans owned by
    // other modules whose classes can fail to load entirely (NoClassDefFoundError when
    // signatures reference optional deps not on the classpath). Those failures are NOT
    // our problem, so they stay at DEBUG. This is a pre-filter — we don't even ask for
    // the bean instance unless there's a task method to register.
    val taskMethods = try {
      beanType.declaredMethods.filter { it.isAnnotationPresent(KoolQueueTask::class.java) }
    } catch (e: Throwable) {
      logger.debug("Cannot reflect on {}, skipping: {}", beanType.name, e.message)
      return
    }

    if (taskMethods.isEmpty()) return

    // Step 2: This bean carries @KoolQueueTask methods, so any failure from here on is
    // a real problem the user needs to know about — silent skipping previously made
    // bean-wiring bugs invisible (the worker simply never ran, with no log to explain it).
    val bean = try {
      beanContext.getBean(beanType)
    } catch (e: Throwable) {
      logger.error(
        "Failed to obtain bean {} which declares {} @KoolQueueTask method(s); these tasks will NOT be scheduled",
        beanType.name, taskMethods.size, e
      )
      return
    }

    taskMethods.forEach { method ->
      try {
        registerTaskMethod(bean, beanType, method)
      } catch (e: Throwable) {
        logger.error("Failed to register @KoolQueueTask {}.{}", beanType.simpleName, method.name, e)
      }
    }
  }

  private fun registerTaskMethod(bean: Any, beanType: Class<*>, method: java.lang.reflect.Method) {
    val annotation = method.getAnnotation(KoolQueueTask::class.java)
    val taskName = annotation.name.ifEmpty { "${beanType.simpleName}.${method.name}" }

    val isSuspend = method.kotlinFunction?.isSuspend == true

    scheduler.registerTask(
      name = taskName,
      task = {
        try {
          if (isSuspend) {
            val result = method.invoke(bean)
            if (result is kotlin.coroutines.Continuation<*>) {
              suspendCoroutine<Unit> { cont: kotlin.coroutines.Continuation<Unit> ->
                method.invoke(bean, cont)
              }
            }
          } else {
            method.invoke(bean)
          }
        } catch (e: Exception) {
          logger.error("Error executing annotated task $taskName", e)
          throw e
        }
      },
      interval = Duration.parse(annotation.interval),
      initialDelay = Duration.parse(annotation.initialDelay),
      maxConcurrency = annotation.maxConcurrency
    )

    logger.info("Auto-registered kool queue task: $taskName")
  }
}