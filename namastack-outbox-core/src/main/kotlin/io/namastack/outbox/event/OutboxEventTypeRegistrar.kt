package io.namastack.outbox.event

import io.namastack.outbox.annotation.OutboxEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * Scans the classpath for `@OutboxEvent`-annotated classes and populates
 * [OutboxEventTypeRegistry] at startup.
 *
 * Runs as a [BeanFactoryPostProcessor] so the registry is fully populated before any
 * [org.springframework.beans.factory.config.BeanPostProcessor] (including
 * [io.namastack.outbox.handler.OutboxHandlerBeanPostProcessor]) executes.
 *
 * Scan packages are resolved from Spring Boot's `AutoConfigurationPackages` (i.e. the
 * package of the `@SpringBootApplication` class) plus any additional packages configured
 * via `namastack.outbox.event-scan-packages`.
 */
internal class OutboxEventTypeRegistrar(
    private val registry: OutboxEventTypeRegistry,
    private val additionalPackages: List<String>,
) : BeanFactoryPostProcessor {
    private val log = LoggerFactory.getLogger(OutboxEventTypeRegistrar::class.java)

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) {
        val packages = resolvePackages(beanFactory)
        if (packages.isEmpty()) {
            log.debug("OutboxEventTypeRegistrar: no scan packages found, skipping @OutboxEvent scan")
            return
        }

        val scanner =
            ClassPathScanningCandidateComponentProvider(false).apply {
                addIncludeFilter(AnnotationTypeFilter(OutboxEvent::class.java))
            }

        for (pkg in packages) {
            for (candidate in scanner.findCandidateComponents(pkg)) {
                val className = candidate.beanClassName ?: continue
                val clazz =
                    try {
                        Class.forName(className, false, Thread.currentThread().contextClassLoader)
                    } catch (ex: ClassNotFoundException) {
                        log.warn("OutboxEventTypeRegistrar: could not load class '$className', skipping", ex)
                        continue
                    }

                val annotation = clazz.getAnnotation(OutboxEvent::class.java) ?: continue
                val logicalName = annotation.name.trim()
                if (logicalName.isEmpty()) continue

                LogicalNameValidator.validate(logicalName, "@OutboxEvent.name on $className")
                annotation.aliases.forEach { alias ->
                    LogicalNameValidator.validate(alias, "@OutboxEvent.aliases on $className")
                }

                registry.register(logicalName, clazz, annotation.aliases.toList())
                log.debug("OutboxEventTypeRegistrar: registered '{}' → {}", logicalName, className)
            }
        }
    }

    private fun resolvePackages(beanFactory: ConfigurableListableBeanFactory): List<String> {
        val autoPackages =
            try {
                AutoConfigurationPackages.get(beanFactory)
            } catch (_: IllegalStateException) {
                emptyList()
            }
        return (autoPackages + additionalPackages).distinct()
    }
}
