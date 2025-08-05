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
package com.joaquindiez.koolQueue.config

import com.joaquindiez.koolQueue.core.KoolQueueScheduler
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Requires
import jakarta.inject.Singleton

@Factory
@Requires(property = "micronaut.scheduler.kool-queue.enabled", value = "true", defaultValue = "true")
class KoolQueueFactory {

  @Singleton
  fun createScheduler(config: KoolQueueSchedulerConfig): KoolQueueScheduler {
    return KoolQueueScheduler(config)
  }



}