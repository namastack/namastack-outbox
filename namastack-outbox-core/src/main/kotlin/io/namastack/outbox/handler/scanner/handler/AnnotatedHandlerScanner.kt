package io.namastack.outbox.handler.scanner.handler

import io.namastack.outbox.annotation.OutboxFallbackHandler
import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.method.fallback.OutboxFallbackHandlerMethod
import io.namastack.outbox.handler.method.fallback.factory.GenericFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.fallback.factory.TypedFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.GenericHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.OutboxHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.TypedHandlerMethodFactory
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import io.namastack.outbox.handler.scanner.HandlerScanResult
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/**
 * Scanner that discovers @OutboxHandler annotated methods with their @OutboxFallbackHandler methods.
 *
 * Finds all methods marked with @OutboxHandler and automatically matches them with
 * @OutboxFallbackHandler methods based on payload type signature.
 *
 * Supports both:
 * - Typed handlers: Single parameter with specific type
 * - Generic handlers: Two parameters (Any + OutboxRecordMetadata)
 *
 * Uses ReflectionUtils for efficient method discovery with proper filtering
 * of bridge and synthetic methods.
 *
 * ## Fallback Matching Strategy
 *
 * For each handler, scans all @OutboxFallbackHandler methods and matches hierarchically:
 * 1. **Exact type match (priority 1)**: Typed fallback with same payload type as handler
 * 2. **Generic fallback (priority 2)**: Fallback with Any payload type (catches all)
 * 3. **First match wins**: If multiple fallbacks of same priority exist, first is used
 * 4. **Warning on duplicates**: Logs warning if multiple fallbacks found
 *
 * This allows both specific typed fallbacks and generic catch-all fallbacks to coexist.
 *
 * @author Roland Beisel
 * @since 0.4.0
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
     * Scans a bean for @OutboxHandler annotated methods and their fallbacks.
     *
     * Algorithm:
     * 1. Find all @OutboxHandler methods using ReflectionUtils
     * 2. Find all @OutboxFallbackHandler methods once (reused for all handlers)
     * 3. For each handler method:
     *    a. Create handler wrapper via factory
     *    b. Find matching @OutboxFallbackHandler via payload type
     *    c. Create fallback wrapper if found
     *    d. Return HandlerScanResult with handler + optional fallback
     *
     * @param bean The bean to scan for @OutboxHandler methods
     * @return List of discovered handler scan results (empty if none found)
     */
    override fun scan(bean: Any): List<HandlerScanResult> {
        // Find all fallback methods once (reused for all handlers)
        val fallbackMethods = ReflectionUtils.findAnnotatedMethods(bean, OutboxFallbackHandler::class.java).toList()

        return ReflectionUtils
            .findAnnotatedMethods(bean, OutboxHandler::class.java)
            .mapNotNull { handlerMethod ->
                // Create handler
                val handler =
                    handlerFactories
                        .firstOrNull { factory -> factory.supports(handlerMethod) }
                        ?.create(bean, handlerMethod)

                if (handler == null) {
                    log.warn(
                        "No factory supports handler method {} in {}",
                        handlerMethod.name,
                        bean::class.simpleName,
                    )
                    return@mapNotNull null
                }

                // Find matching fallback based on payload type
                val fallback = findMatchingFallback(bean, handlerMethod, fallbackMethods)

                HandlerScanResult(handler, fallback)
            }.toList()
    }

    /**
     * Finds a matching fallback handler for a given handler method.
     *
     * Matches based on hierarchical strategy:
     * 1. **Exact type match**: Typed fallback with same payload type (preferred)
     * 2. **Generic fallback**: Fallback with Any payload type (fallback)
     *
     * If multiple fallbacks of same priority exist, uses first one and logs warning.
     *
     * Examples:
     * - Typed handler (OrderEvent) + Typed fallback (OrderEvent) → Exact match ✅
     * - Typed handler (OrderEvent) + Generic fallback (Any) → Generic match ✅
     * - Generic handler (Any) + Generic fallback (Any) → Exact match ✅
     *
     * @param bean The bean containing the methods
     * @param handlerMethod The handler method to find a fallback for
     * @param fallbackMethods All fallback methods in the bean
     * @return Fallback handler method or null if no match found
     */
    private fun findMatchingFallback(
        bean: Any,
        handlerMethod: Method,
        fallbackMethods: List<Method>,
    ): OutboxFallbackHandlerMethod? {
        val handlerPayloadType = handlerMethod.parameterTypes.firstOrNull() ?: return null

        // 1. Try exact type match first (typed fallback for typed handler)
        val exactMatches =
            fallbackMethods.filter {
                it.parameterCount == 3 && it.parameterTypes[0] == handlerPayloadType
            }

        if (exactMatches.isNotEmpty()) {
            return createFallbackFromMatch(bean, exactMatches, handlerPayloadType, "exact type")
        }

        // 2. Try generic fallback (Any) as fallback for typed handler
        if (handlerPayloadType != Any::class.java) {
            val genericMatches =
                fallbackMethods.filter {
                    it.parameterCount == 3 && it.parameterTypes[0] == Any::class.java
                }

            if (genericMatches.isNotEmpty()) {
                return createFallbackFromMatch(bean, genericMatches, handlerPayloadType, "generic")
            }
        }

        return null
    }

    /**
     * Creates a fallback handler from a list of matching methods.
     *
     * Warns if multiple matches exist and uses the first one.
     *
     * @param bean The bean containing the methods
     * @param matchingMethods List of matching fallback methods
     * @param handlerPayloadType The payload type of the handler
     * @param matchType Type of match for logging ("exact type" or "generic")
     * @return Fallback handler method or null if creation failed
     */
    private fun createFallbackFromMatch(
        bean: Any,
        matchingMethods: List<Method>,
        handlerPayloadType: Class<*>,
        matchType: String,
    ): OutboxFallbackHandlerMethod? {
        // Warn if multiple fallbacks found
        if (matchingMethods.size > 1) {
            log.warn(
                "Multiple {} fallback handlers found for payload type {} in {}. Using first: {}. Others: {}",
                matchType,
                handlerPayloadType.simpleName,
                bean::class.simpleName,
                matchingMethods[0].name,
                matchingMethods.drop(1).joinToString { it.name },
            )
        }

        val fallbackMethod = matchingMethods.first()

        // Find factory and create fallback handler
        return fallbackFactories
            .firstOrNull { it.supports(fallbackMethod) }
            ?.let { factory ->
                try {
                    factory.create(bean, fallbackMethod)
                } catch (e: Exception) {
                    log.error(
                        "Failed to create fallback handler from method {} in {}",
                        fallbackMethod.name,
                        bean::class.simpleName,
                        e,
                    )
                    null
                }
            }
            ?: run {
                log.warn(
                    "No factory supports fallback method {} in {}",
                    fallbackMethod.name,
                    bean::class.simpleName,
                )
                null
            }
    }
}
