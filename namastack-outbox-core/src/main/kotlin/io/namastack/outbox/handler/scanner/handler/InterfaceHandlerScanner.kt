package io.namastack.outbox.handler.scanner.handler

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.method.fallback.factory.GenericFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.fallback.factory.TypedFallbackHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.GenericHandlerMethodFactory
import io.namastack.outbox.handler.method.handler.factory.TypedHandlerMethodFactory
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import io.namastack.outbox.handler.scanner.HandlerScanResult
import org.springframework.util.ClassUtils

/**
 * Scanner that discovers handler implementations via interface inheritance.
 *
 * Finds beans that implement:
 * - [OutboxTypedHandler]: Typed handler for a specific payload type T
 * - [OutboxHandler]: Generic handler for any payload type
 * - [OutboxTypedHandlerWithFallback]: Typed handler with fallback
 * - [OutboxHandlerWithFallback]: Generic handler with fallback
 *
 * For beans implementing the "WithFallback" variants, both the handler and fallback
 * methods are discovered and paired together in the result.
 *
 * This scanner complements [AnnotatedHandlerScanner] for handlers that use
 * interface-based registration instead of @OutboxHandler annotations.
 *
 * A bean can implement both interfaces and will register both handlers.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
class InterfaceHandlerScanner : HandlerScanner {
    private val typedHandlerFactory = TypedHandlerMethodFactory()
    private val genericHandlerFactory = GenericHandlerMethodFactory()
    private val typedFallbackFactory = TypedFallbackHandlerMethodFactory()
    private val genericFallbackFactory = GenericFallbackHandlerMethodFactory()

    /**
     * Scans a bean for OutboxHandler and OutboxTypedHandler interface implementations.
     *
     * Algorithm:
     * 1. Check if bean implements OutboxTypedHandler<T> or OutboxHandler
     * 2. If not, return empty list (no handlers to register)
     * 3. Use the Spring bean name as handler ID when the bean is a lambda
     * 4. If OutboxTypedHandler<T>: Create typed handler
     * 5. If bean also implements WithFallback variant: Create fallback and pair with handler
     * 6. If OutboxHandler: Create generic handler
     * 7. If bean also implements WithFallback variant: Create fallback and pair with handler
     * 8. Return all discovered handler scan results
     *
     * Example:
     * ```kotlin
     * @Component
     * class OrderHandler : OutboxTypedHandler<OrderCreatedEvent> {
     *     override fun handle(payload: OrderCreatedEvent) { ... }
     * }
     * ```
     * → Returns: HandlerScanResult(handler, fallback=null)
     *
     * ```kotlin
     * @Component
     * class OrderHandlerWithFallback : OutboxTypedHandlerWithFallback<OrderCreatedEvent> {
     *     override fun handle(payload: OrderCreatedEvent) { ... }
     *     override fun handleFailure(payload: OrderCreatedEvent, ...) { ... }
     * }
     * ```
     * → Returns: HandlerScanResult(handler, fallback=TypedFallbackHandlerMethod)
     *
     * @param bean The bean to scan for handler interface implementations
     * @param beanName The bean's name, used as the stable ID for lambda handlers
     * @return List of discovered handler scan results
     */
    override fun scan(
        bean: Any,
        beanName: String,
    ): List<HandlerScanResult> {
        if (bean !is OutboxTypedHandler<*> && bean !is OutboxHandler) return emptyList()

        val results = mutableListOf<HandlerScanResult>()
        val handlerIdOverride = getHandlerIdOverride(bean, beanName)

        // Register typed handler if bean implements OutboxTypedHandler<T>
        if (bean is OutboxTypedHandler<*>) {
            val handler = typedHandlerFactory.createFromInterface(bean)
            handlerIdOverride?.let { handler.id = it }

            val fallback =
                if (bean is OutboxTypedHandlerWithFallback<*>) {
                    typedFallbackFactory.createFromInterface(bean)
                } else {
                    null
                }
            results += HandlerScanResult(handler, fallback)
        }

        // Register generic handler if bean implements OutboxHandler
        if (bean is OutboxHandler) {
            val handler = genericHandlerFactory.createFromInterface(bean)
            handlerIdOverride?.let { handler.id = it }

            val fallback =
                if (bean is OutboxHandlerWithFallback) {
                    genericFallbackFactory.createFromInterface(bean)
                } else {
                    null
                }
            results += HandlerScanResult(handler, fallback)
        }

        return results
    }

    private fun getHandlerIdOverride(
        bean: Any,
        beanName: String,
    ): String? = beanName.takeIf { ClassUtils.isLambdaClass(ReflectionUtils.getTargetClass(bean)) }
}
