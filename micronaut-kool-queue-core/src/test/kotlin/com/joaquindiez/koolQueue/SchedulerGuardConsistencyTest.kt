package com.joaquindiez.koolQueue

import com.joaquindiez.koolQueue.core.KoolQueueScheduler
import io.micronaut.context.annotation.Requires
import io.micronaut.core.io.service.SoftServiceLoader
import io.micronaut.inject.BeanDefinitionReference
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `KoolQueueScheduler` is conditional on `micronaut.scheduler.kool-queue.enabled`.
 * Any bean that injects it must carry the same condition, or setting the flag to
 * `false` does not disable Kool Queue: it breaks context startup with a
 * `NoSuchBeanException` pointing at a class the user never configured.
 *
 * This pins the invariant rather than a single class, so a bean added later that
 * forgets the guard fails here instead of in somebody's deployment.
 *
 * See https://github.com/joaquindiez/micronaut-kool-queue/issues/3
 */
class SchedulerGuardConsistencyTest {

    private val enabledProperty = "micronaut.scheduler.kool-queue.enabled"

    @Test
    fun `every bean injecting KoolQueueScheduler is guarded by the enabled property`() {
        val unguarded = koolQueueBeans()
            .filter { definition ->
                definition.constructor.arguments.any { it.type == KoolQueueScheduler::class.java }
            }
            .filterNot { definition ->
                definition.annotationMetadata
                    .getAnnotationValuesByType(Requires::class.java)
                    .any { it.stringValue("property").orElse(null) == enabledProperty }
            }
            .map { it.beanType.simpleName }
            .toList()

        assertTrue(
            unguarded.isEmpty(),
            "These beans inject KoolQueueScheduler without @Requires($enabledProperty): " +
                "$unguarded. With enabled=false the scheduler does not exist, so the context " +
                "fails to start instead of Kool Queue being disabled.",
        )
    }

    private fun koolQueueBeans() =
        SoftServiceLoader.load(BeanDefinitionReference::class.java)
            .collectAll()
            .asSequence()
            .filter { it.beanType.name.startsWith("com.joaquindiez.koolQueue") }
            .mapNotNull { runCatching { it.load() }.getOrNull() }
}
