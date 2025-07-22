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


import com.joaquindiez.koolQueue.jobs.ApplicationJob
import jakarta.inject.Singleton
import org.slf4j.LoggerFactory


@Singleton
class TestJobs : ApplicationJob<String>() {

  private val logger = LoggerFactory.getLogger(javaClass)

  override fun getType(): Class<String> {
    return String::class.java
  }

  override fun process(data: Any): Result<Boolean> {
    logger.info("Procesando Test Jobs -> $data")
    return Result.success(true)
  }


}
