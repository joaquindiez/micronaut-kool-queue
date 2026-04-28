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
class TestController(
  val testJobs: TestJobs,
  val reportJobs: ReportJobs,
) {

  @Get
  fun addEmail(): JobReference {
    val jobRef = testJobs.processLater("hello-emails")
    println("Job enqueued: $jobRef")
    return jobRef
  }

  @Get("/report")
  fun addReport(): JobReference {
    val jobRef = reportJobs.processLater("hello-reports")
    println("Job enqueued: $jobRef")
    return jobRef
  }

  @Get("/override")
  fun addOverride(): JobReference {
    // Per-call override — routes to a queue this worker is NOT configured to poll
    val jobRef = testJobs.processLater("hello-vip", queue = "vip")
    println("Job enqueued: $jobRef")
    return jobRef
  }

  @Get("/scheduled")
  fun addTestScheduled(): JobReference {
    val jobRef = testJobs.processLater("hello-scheduled", scheduledAt = LocalDateTime.now().plusMinutes(1))
    println("Job enqueued: $jobRef")
    return jobRef
  }
}
