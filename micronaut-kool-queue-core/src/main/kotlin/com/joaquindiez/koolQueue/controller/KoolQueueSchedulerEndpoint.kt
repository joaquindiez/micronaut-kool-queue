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
import io.micronaut.http.HttpRequest
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

  companion object {
    private const val DEFAULT_PAGE = 0
    private const val DEFAULT_SIZE = 20
    private const val MAX_SIZE = 100
  }

  @Read
  fun getStats(): Map<String, Any> {
    return scheduler.getStats()
  }

  // GET /kool-queue-scheduler/tasks?page=0&size=20
  // GET /kool-queue-scheduler/in-progress?page=0&size=20
  @Read
  fun getTasks(@Selector selector: String?, request: HttpRequest<*>): Any {
    return when (selector) {
      "tasks" -> getTaskList(request)
      "in-progress" -> getInProgressTasks(request)
      else -> mapOf("error" to "Invalid selector. Use: tasks or in-progress")
    }
  }


  private fun getTaskList(request: HttpRequest<*>): Any {
    val page = request.parameters.get("page")?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_PAGE
    val size = request.parameters.get("size")?.toIntOrNull()?.coerceIn(1, MAX_SIZE) ?: DEFAULT_SIZE

    return taskService.findAllJobsPendingPaged(page, size)
  }


  private fun getInProgressTasks(request: HttpRequest<*>): Any {
    val page = request.parameters.get("page")?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_PAGE
    val size = request.parameters.get("size")?.toIntOrNull()?.coerceIn(1, MAX_SIZE) ?: DEFAULT_SIZE

    return taskService.findInProgressTasksPaged(page, size)
  }

}