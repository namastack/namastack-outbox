package io.namastack.outbox.handler.scanner.handler

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.factory.GenericFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.fallback.factory.TypedFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.OutboxHandlerMethod
import io.namastack.outbox.handler.method.handler.factory.GenericHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.OutboxHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.TypedHandlerMethodFactory
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import io.namastack.outbox.handler.scanner.HandlerScanResult
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Scans beans for @OutboxHandler and @OutboxFallbackHandler annotated methods.
 *
 * Matches fallback handlers to handlers based on payload type compatibility.
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class AnnotatedHandlerScanner : HandlerScanner {
    private val log = LoggerFactory.getLogger(AnnotatedHandlerScanner::class.java)

    private val handlerFactories: List<OutboxHandlerMethodFactory> =
        listOf(
            TypedHandlerMethodFactory(),
            GenericHandlerMethodFactory(),
        )

    private val fallbackFactories =
        listOf(
            TypedFallbackHandlerMethodFactory(),
            GenericFallbackHandlerMethodFactory(),
        )

    /**
     * Scans bean for handler and fallback methods, matching them by payload type.
     */
    override fun scan(bean: Any): List<HandlerScanResult> {
        val fallbackMethods = getFallbackMethods(bean)

        return ReflectionUtils
            .findAnnotatedMethods(bean, OutboxHandler::class.java)
            .mapNotNull { handlerMethod ->
                val handler = createHandler(bean, handlerMethod) ?: return@mapNotNull null
                val fallback = findMatchingFallback(bean, handlerMethod, fallbackMethods)

                HandlerScanResult(handler, fallback)
            }.toList()
    }

    /**
     * Collects all valid fallback methods from bean.
     */
    private fun getFallbackMethods(bean: Any): List<Method> =
        ReflectionUtils
            .findAnnotatedMethods(bean, OutboxFallbackHandler::class.java)
            .filter { fallbackFactories.any { factory -> factory.supports(it) } }
            .toList()

    /**
     * Creates handler wrapper using appropriate factory.
     */
    private fun createHandler(
        bean: Any,
        handlerMethod: Method,
    ): OutboxHandlerMethod? {
        val handlerFactory = handlerFactories.firstOrNull { factory -> factory.supports(handlerMethod) }
        if (handlerFactory == null) {
            log.warn(
                "No factory supports handler method {} in {}",
                handlerMethod.name,
                bean::class.simpleName,
            )
            return null
        }

        return handlerFactory.create(bean, handlerMethod)
    }

    /**
     * Finds fallback method matching handler's payload type.
     */
    private fun findMatchingFallback(
        bean: Any,
        handlerMethod: Method,
        fallbackMethods: List<Method>,
    ): OutboxFallbackHandlerMethod? {
        val handlerPayloadType = handlerMethod.parameterTypes.first()

        val matchingMethods = fallbackMethods.filter { it.parameterTypes[0] == handlerPayloadType }

        return createFallbackFromMatch(bean, matchingMethods, handlerPayloadType)
    }

    /**
     * Creates fallback handler from first matching method. Validates uniqueness.
     */
    private fun createFallbackFromMatch(
        bean: Any,
        matchingMethods: List<Method>,
        handlerPayloadType: Class<*>,
    ): OutboxFallbackHandlerMethod? {
        if (matchingMethods.isEmpty()) return null

        if (matchingMethods.size > 1) {
            log.warn(
                "Multiple fallback handlers found for payload type ${handlerPayloadType.simpleName} " +
                    "in ${bean::class.simpleName}: ${matchingMethods.joinToString { it.name }}. " +
                    "Only the first one (${matchingMethods.first().name}) will be registered.",
            )
        }

        val fallbackMethod = matchingMethods.first()

        return fallbackFactories
            .firstOrNull { it.supports(fallbackMethod) }
            ?.create(bean, fallbackMethod)
    }
}
