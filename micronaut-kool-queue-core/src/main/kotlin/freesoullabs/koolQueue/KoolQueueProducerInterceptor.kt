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
package freesoullabs.koolQueue

import io.micronaut.aop.InterceptorBean
import io.micronaut.aop.MethodInterceptor
import io.micronaut.aop.MethodInvocationContext
import jakarta.inject.Singleton


@InterceptorBean(KoolQueueProducer::class)
@Singleton
class KoolQueueProducerInterceptor : MethodInterceptor<Any, Any> {

  override fun intercept(context: MethodInvocationContext<Any, Any>): Any? {
    println("Antes de ejecutar el método: ${context.targetMethod.name}")

    // Llamar al método original
    val result = context.proceed()

    println("Después de ejecutar el método: ${context.targetMethod.name}")

    return result
  }
}
