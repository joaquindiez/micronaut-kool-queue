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
package com.joaquindiez.koolQueue.controller

import com.joaquindiez.koolQueue.core.KoolQueueScheduler
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import io.micronaut.context.annotation.Requires
import io.micronaut.management.endpoint.annotation.Endpoint
import io.micronaut.management.endpoint.annotation.Read
import io.micronaut.management.endpoint.annotation.Selector

// ==========================================
// ENDPOINT DE MANAGEMENT
// ==========================================

@Endpoint(id = "kool-queue-scheduler")
@Requires(property = "micronaut.scheduler.kool-queue.enable-management-endpoints", value = "true", defaultValue = "true")
class KoolQueueSchedulerEndpoint(
  private val scheduler: KoolQueueScheduler,
  private val taskService: KoolQueueJobsService
) {

  @Read
  fun getStats(): Map<String, Any> {
    return scheduler.getStats()
  }

  // GET /kool-queue-scheduler/tasks
  @Read
  fun getTasks(@Selector selector: String?): Any {
    return when (selector) {
      "tasks" -> getTaskList()
      "in-progress" -> getInProgressTasks()
      //"registered" -> getRegisteredTasks()
      else -> mapOf("error" to "Invalid selector. Use: tasks, active, or registered")
    }
  }


  private fun getTaskList(): Any {
    return taskService.findAllJobsPending();
  }


  private fun getInProgressTasks(): Any {
    return taskService.findInProgressTasks();
  }



}