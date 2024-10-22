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

import io.micronaut.context.BeanContext
import io.micronaut.json.JsonMapper
import io.micronaut.scheduling.annotation.Scheduled
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

  @Scheduled(fixedRate = "2s", fixedDelay = "5s")
  fun checkNextSendEmailTask() {

    logger.info("Check next Jobs to Run")
    val sendEmailTasks =  taskService.findNextJobsPending()

    for (jobTask in sendEmailTasks) {

      val className = jobTask.className
      //val applicationJob: ApplicationJob<*>? = beanContext.getBean(ApplicationJob::class.java,  Qualifiers.byName(beanName))
      val applicationJob = beanContext.getBean(Class.forName(className))
      val inputStream = ByteArrayInputStream(jobTask.metadata.toByteArray())
      if (applicationJob is ApplicationJob<*>?) {
        val sendSurveyTask = jsonMapper.readValue(inputStream, applicationJob.getType())
        val result = applicationJob.process(sendSurveyTask!!)
        result.fold(
          onSuccess = {   taskService.finishSuccessTask(jobTask) },
          onFailure = {   taskService.finishOnErrorTask(jobTask)}
        )

      }else{
        logger.error("Job className=$className not valid")
        taskService.finishOnErrorTask(jobTask)
      }
    }

  }
}
