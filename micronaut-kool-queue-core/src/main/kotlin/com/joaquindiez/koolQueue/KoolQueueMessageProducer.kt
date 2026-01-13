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
package com.joaquindiez.koolQueue

import com.joaquindiez.koolQueue.domain.JobReference
import java.time.LocalDateTime

/**
 * Interface for producing messages to the Kool Queue.
 */
interface KoolQueueMessageProducer {
  /**
   * Sends a message to the queue for processing.
   *
   * @param msg The message payload to be processed
   * @param className The class that will process this message
   * @param scheduledAt Optional time to schedule the job (null for immediate execution)
   * @return JobReference containing the job ID for tracking
   */
  fun send(msg: Any, className: Class<*>, scheduledAt: LocalDateTime?): JobReference
}
