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
import io.micronaut.context.BeanContext
import io.micronaut.json.JsonMapper
import jakarta.inject.Inject
import jakarta.inject.Singleton
import com.joaquindiez.koolQueue.services.KoolQueueJobsService
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream


@Singleton
class KoolQueueScheduledJob(
  private val taskService: KoolQueueJobsService,
  private val jsonMapper: JsonMapper) {

  @Inject
  lateinit var beanContext: BeanContext

  private val logger = LoggerFactory.getLogger(javaClass)

  //@Scheduled(fixedRate = "2s", fixedDelay = "5s")
  @KoolQueueTask(name = "checkKoolTasksTasks", interval = "2s", initialDelay = "10s")
  fun checkPendingTasks() {

    val nextPendingTasks =  taskService.findNextJobsPending(limit = 1)
    logger.debug("Check next Jobs to Run pending jobs to Run ${nextPendingTasks.size}")

    for (jobTask in nextPendingTasks) {

      val className = jobTask.className
      val applicationJob = beanContext.getBean(Class.forName(className))
      val inputStream = ByteArrayInputStream(jobTask.metadata.toByteArray())
      if (applicationJob is ApplicationJob<*>?) {
        val koolTask = jsonMapper.readValue(inputStream, applicationJob.getType())
        try {
          val result = applicationJob.process(koolTask!!)
          result.fold(
            onSuccess = {
              logger.info("Job taskId=${jobTask.jobId} className=$className finished successfully")
              taskService.finishSuccessTask(jobTask)
            },
            onFailure = {
              logger.error("Job taskId=${jobTask.jobId} className=$className finished onError")
              taskService.finishOnErrorTask(jobTask)
            }
          )
        }catch (ex: Exception){
          logger.error("Job taskId=${jobTask.jobId} className=$className UnExpected failure")
          taskService.finishOnErrorTask(jobTask)
        }

      }else{
        logger.error("Job className=$className not valid taskId=${jobTask.jobId}")
        taskService.finishOnErrorTask(jobTask)
      }
    }

  }
}
