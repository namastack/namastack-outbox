package io.namastack.outbox.handler.scanner

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.OutboxHandlerMethodFactory
import org.springframework.aop.support.AopUtils
import org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation

/**
 * Scanner that discovers @OutboxHandler annotated methods in beans.
 *
 * Finds all methods marked with @OutboxHandler and uses the appropriate
 * factory to create handler method wrappers based on method signature.
 *
 * Supports both:
 * - Typed handlers: Single parameter with specific type
 * - Generic handlers: Two parameters (Any + OutboxRecordMetadata)
 *
 * @param factories List of handler method factories for creating handler wrappers
 */
class AnnotatedHandlerScanner(
    private val factories: List<OutboxHandlerMethodFactory>,
) : HandlerScanner {
    /**
     * Scans a bean for @OutboxHandler annotated methods.
     *
     * Discovers handler methods by scanning the bean's class methods and searching
     * for @OutboxHandler annotations in the method hierarchy (class, interfaces, superclasses).
     *
     * Algorithm:
     * 1. Get all methods from bean's class
     * 2. Filter out bridge methods (synthetic methods for generics)
     * 3. Filter methods that have @OutboxHandler annotation (searches in hierarchy)
     * 4. For each annotated method:
     *    a. Find the first factory that supports this method's signature
     *    b. Use the factory to create an OutboxHandlerMethod
     * 5. Return all discovered handler methods
     *
     * Bridge methods are excluded to ensure we register the concrete implementation
     * (e.g., `handle(RegisterUserEvent)`) instead of the generic bridge method
     * (e.g., `handle(HttpEvent)`) when implementing generic interfaces.
     *
     * Uses Spring's AnnotatedElementUtils to find annotations in the method hierarchy,
     * including interfaces and superclasses. This correctly handles:
     * - Annotations on overridden interface methods
     * - Annotations on overridden superclass methods
     * - Meta-annotations
     * - Generic interfaces with type erasure
     *
     * Limitation: Does not discover Kotlin default interface methods that are not
     * overridden in the implementing class. Workaround: Add an empty override that
     * delegates to the interface default implementation.
     *
     * If a method's signature doesn't match any factory's supports() check,
     * it is skipped (mapNotNull behavior).
     *
     * @param bean The bean to scan for @OutboxHandler methods
     * @return List of discovered OutboxHandlerMethod instances
     */
    override fun scan(bean: Any): List<OutboxHandlerMethod> =
        getClass(bean)
            .methods
            .filterNot { it.isBridge }
            .filter { findMergedAnnotation(it, OutboxHandler::class.java) != null }
            .mapNotNull { method ->
                factories.firstOrNull { it.supports(method) }?.create(bean, method)
            }

    /**
     * Resolves the Kotlin class (`KClass<*>`) of the given bean.
     *
     * If the bean is an AOP proxy, retrieves the target class behind the proxy.
     * Otherwise, returns the bean's own class.
     *
     * @param bean The object instance whose class is to be resolved
     * @return The resolved `KClass<*>` of the bean or its target class
     */
    private fun getClass(bean: Any): Class<*> =
        if (AopUtils.isAopProxy(bean)) {
            AopUtils.getTargetClass(bean)
        } else {
            bean::class.java
        }
}
