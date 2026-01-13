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

import com.joaquindiez.koolQueue.domain.JobReference
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import java.time.LocalDateTime


@Controller("/task")
class TestController( val testJobs: TestJobs) {

  @Get
  fun addTest(): JobReference{

    val jobRef = testJobs.processLater("Hello")
    println("Job ID: ${jobRef.jobId}")
    // Encolar y obtener referencia
    return jobRef

  }

  @Get("/scheduled")
  fun addTestScheduled(): JobReference{

    val jobRef = testJobs.processLater("Hello Scheduled", scheduledAt = LocalDateTime.now().plusMinutes(1))
    println("Job ID: ${jobRef}")

    return jobRef
  }
}
