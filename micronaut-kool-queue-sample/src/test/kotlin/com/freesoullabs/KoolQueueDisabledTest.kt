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
package com.freesoullabs

import com.joaquindiez.koolQueue.core.KoolQueueScheduler
import io.micronaut.context.BeanContext
import io.micronaut.context.annotation.Property
import io.micronaut.runtime.EmbeddedApplication
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `micronaut.scheduler.kool-queue.enabled: false` must disable Kool Queue, not
 * break the application.
 *
 * Before the fix this did not start at all: KoolQueueScheduler is conditional on
 * that property, but KoolQueueAnnotationProcessor, KoolQueueScheduledJob and
 * KoolQueueSchedulerEndpoint injected it unconditionally, so the context failed
 * with a NoSuchBeanException pointing at a class the user never configured.
 *
 * See https://github.com/joaquindiez/micronaut-kool-queue/issues/3
 */
@MicronautTest
@Property(name = "micronaut.scheduler.kool-queue.enabled", value = "false")
class KoolQueueDisabledTest {

  @Inject
  lateinit var application: EmbeddedApplication<*>

  @Inject
  lateinit var beanContext: BeanContext

  @Test
  fun `the application starts with Kool Queue disabled`() {
    assertTrue(application.isRunning)
  }

  @Test
  fun `the scheduler is absent when disabled`() {
    assertFalse(
      beanContext.containsBean(KoolQueueScheduler::class.java),
      "With enabled=false the scheduler must not be created",
    )
  }
}
