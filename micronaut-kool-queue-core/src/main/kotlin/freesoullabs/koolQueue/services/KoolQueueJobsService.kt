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
package freesoullabs.koolQueue.services

import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import freesoullabs.koolQueue.domain.TaskStatus
import freesoullabs.koolQueue.domain.KoolQueueJobs
import freesoullabs.koolQueue.repository.JobsRepository
import org.slf4j.LoggerFactory

import java.time.LocalDateTime

@Singleton
open class KoolQueueJobsService(
  private val jobsRepository: JobsRepository) {
  private val logger = LoggerFactory.getLogger(javaClass)

  private val limit = 5

  @Transactional
  open fun findAllTasks(): List<KoolQueueJobs> {
    return this.jobsRepository.findAll()
  }

  /**
   *
   * just for demo purpose
   */
  @Transactional
  open fun addTask(task: KoolQueueJobs): KoolQueueJobs {
    val id =  this.jobsRepository.save(task)
    return task.copy(id = id)
  }


  @Transactional
  open fun finishSuccessTask(task: KoolQueueJobs): KoolQueueJobs {
    val i =  this.jobsRepository.update(task.id!!, TaskStatus.DONE, LocalDateTime.now())
    return task
  }

  @Transactional
  open fun finishOnErrorTask(task: KoolQueueJobs): KoolQueueJobs {
    val i =  this.jobsRepository.update(task.id!!, TaskStatus.ERROR, LocalDateTime.now())
    return task
  }


  @Transactional
  open fun findNextJobsPending(): List<KoolQueueJobs>  {

    val taskList = this.jobsRepository.findNextJobsPending()
    logger.debug("Result find Task $taskList")
    for (task in taskList) {
      // Si se encuentra la tarea, se procesa y actualiza su estado
      task.let {
        it.status = TaskStatus.IN_PROGRESS
        val i =  this.jobsRepository.update(task.id!!, task.status)
      }
    }
    return taskList
  }

}
