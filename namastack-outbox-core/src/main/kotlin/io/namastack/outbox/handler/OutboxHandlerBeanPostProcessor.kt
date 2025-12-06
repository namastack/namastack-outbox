package io.namastack.outbox.handler

import io.namastack.outbox.handler.method.GenericHandlerMethod
import io.namastack.outbox.handler.method.GenericHandlerMethodFactory
import io.namastack.outbox.handler.method.TypedHandlerMethod
import io.namastack.outbox.handler.method.TypedHandlerMethodFactory
import io.namastack.outbox.handler.scanner.AnnotatedHandlerScanner
import io.namastack.outbox.handler.scanner.InterfaceHandlerScanner
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Spring BeanPostProcessor that discovers and registers handler methods.
 *
 * Called for each bean after Spring instantiates it. Scans for handlers using
 * multiple strategies:
 * 1. Annotation-based: Methods marked with @OutboxHandler
 * 2. Interface-based: Beans implementing OutboxHandler or OutboxTypedHandler<T>
 *
 * Discovered handlers are registered in the OutboxHandlerRegistry for later
 * invocation during record processing.
 *
 * ## Timing
 *
 * Runs in the post-initialization phase (after Constructor + Dependency Injection).
 * This ensures:
 * - Bean is fully instantiated and initialized
 * - All dependencies are injected
 * - The bean is ready to handle invocations
 *
 * ## Handler Types Supported
 *
 * - **Typed Handlers**: Single parameter with specific payload type
 *   ```kotlin
 *   @OutboxHandler
 *   fun handle(payload: OrderCreatedEvent) { ... }
 *   ```
 *
 * - **Generic Handlers**: Two parameters (Any + OutboxRecordMetadata)
 *   ```kotlin
 *   @OutboxHandler
 *   fun handle(payload: Any, metadata: OutboxRecordMetadata) { ... }
 *   ```
 *
 * - **Interface Implementations**:
 *   ```kotlin
 *   class MyHandler : OutboxTypedHandler<OrderCreatedEvent> {
 *       override fun handle(payload: OrderCreatedEvent) { ... }
 *   }
 *   ```
 *
 * @param registry The handler registry to store discovered handlers
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
internal class OutboxHandlerBeanPostProcessor(
    private val registry: OutboxHandlerRegistry,
) : BeanPostProcessor {
    /**
     * List of scanners that discover handlers in different ways.
     * Each scanner uses a different strategy (annotations vs interfaces).
     */
    private val scanners =
        listOf(
            AnnotatedHandlerScanner(factories = listOf(TypedHandlerMethodFactory(), GenericHandlerMethodFactory())),
            InterfaceHandlerScanner(),
        )

    /**
     * Processes a bean after Spring instantiation.
     *
     * Algorithm:
     * 1. For each scanner in the list:
     *    a. Scan the bean for handlers
     *    b. Register each discovered handler based on its type
     * 2. Return the bean unchanged (just registering side effects)
     *
     * @param bean The newly instantiated bean
     * @param beanName The name of the bean in the Spring context
     * @return The original bean unchanged
     */
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        // Scan for handlers using all configured scanners
        scanners.forEach { scanner ->
            scanner.scan(bean).forEach { handlerMethod ->
                // Register handler based on its type
                when (handlerMethod) {
                    is TypedHandlerMethod -> {
                        registry.registerTypedHandler(
                            handlerMethod = handlerMethod,
                            paramType = handlerMethod.paramType,
                        )
                    }

                    is GenericHandlerMethod -> {
                        registry.registerGenericHandler(handlerMethod = handlerMethod)
                    }
                }
            }
        }

        return bean
    }
}
