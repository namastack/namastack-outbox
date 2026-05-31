package io.namastack.outbox.serializer

import io.namastack.outbox.OutboxPayloadSerializer
import io.namastack.outbox.annotation.OutboxEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurationPackages
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * Scans the classpath at startup for classes annotated with [OutboxEvent] that declare a
 * non-default [OutboxEvent.serializer], validates that a bean of that type exists in the
 * application context, and builds the type-to-serializer map consumed by
 * [OutboxPayloadSerializerRegistry].
 *
 * Scan packages mirror those used by the event type registrar:
 * Spring Boot auto-configuration packages + `namastack.outbox.event-scan-packages`.
 */
class OutboxSerializerRegistrar(
    private val additionalPackages: List<String>,
) : ApplicationContextAware {
    private val log = LoggerFactory.getLogger(OutboxSerializerRegistrar::class.java)
    private lateinit var applicationContext: ApplicationContext

    override fun setApplicationContext(context: ApplicationContext) {
        applicationContext = context
    }

    fun buildSerializerMap(): Map<Class<*>, OutboxPayloadSerializer> {
        val packages = resolvePackages()
        if (packages.isEmpty()) {
            log.debug("OutboxSerializerRegistrar: no scan packages found, skipping @OutboxEvent serializer scan")
            return emptyMap()
        }

        val result = mutableMapOf<Class<*>, OutboxPayloadSerializer>()
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
                        log.warn("OutboxSerializerRegistrar: could not load class '$className', skipping", ex)
                        continue
                    }

                val annotation = AnnotationUtils.findAnnotation(clazz, OutboxEvent::class.java) ?: continue
                val serializerClass = annotation.serializer

                if (serializerClass == OutboxPayloadSerializer::class) continue

                val serializer =
                    try {
                        applicationContext.getBean(serializerClass.java)
                    } catch (ex: Exception) {
                        throw IllegalStateException(
                            "@OutboxEvent on $className specifies serializer " +
                                "${serializerClass.qualifiedName} but no such bean was found in the " +
                                "application context. Declare a @Bean of that type.",
                            ex,
                        )
                    }

                result[clazz] = serializer
                log.debug(
                    "OutboxSerializerRegistrar: {} → {}",
                    className,
                    serializerClass.simpleName,
                )
            }
        }
        return result
    }

    private fun resolvePackages(): List<String> {
        val autoPackages =
            try {
                AutoConfigurationPackages.get(applicationContext)
            } catch (_: IllegalStateException) {
                emptyList()
            }
        return (autoPackages + additionalPackages).distinct()
    }
}
