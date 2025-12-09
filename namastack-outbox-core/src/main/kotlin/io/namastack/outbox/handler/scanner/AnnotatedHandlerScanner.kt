package io.namastack.outbox.handler.scanner

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.handler.method.OutboxHandlerMethod
import io.namastack.outbox.handler.method.OutboxHandlerMethodFactory
import org.springframework.aop.support.AopUtils
import java.lang.reflect.Method
import kotlin.reflect.KClass

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
     * Processes all methods in the bean's class, filters those with @OutboxHandler annotation,
     * and uses the appropriate factory to create OutboxHandlerMethod instances.
     *
     * Algorithm:
     * 1. Get all methods from bean's class
     * 2. Filter methods that have @OutboxHandler annotation
     * 3. For each annotated method:
     *    a. Find the first factory that supports this method's signature
     *    b. Use the factory to create an OutboxHandlerMethod
     * 4. Return all discovered handler methods
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
            .filter { hasAnnotationInHierarchy(it, OutboxHandler::class) }
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

    /**
     * Checks if the given method or any method with the same signature in its interfaces or superclass
     * is annotated with the specified annotation.
     *
     * @param method The Java reflection Method to check for the annotation
     * @param annotationClass The KClass of the annotation to look for
     * @return `true` if the annotation is present in the method hierarchy, `false` otherwise
     */
    private fun <A : Annotation> hasAnnotationInHierarchy(
        method: Method,
        annotationClass: KClass<A>,
    ): Boolean {
        // Check the method itself
        if (method.isAnnotationPresent(annotationClass.java)) return true

        val name = method.name
        val parameterTypes = method.parameterTypes

        // Check interfaces
        method.declaringClass.interfaces.forEach { iface ->
            try {
                val ifaceMethod = iface.getMethod(name, *parameterTypes)
                if (ifaceMethod.isAnnotationPresent(annotationClass.java)) return true
            } catch (_: NoSuchMethodException) {
                // Method not found in this interface, continue
            }
        }

        // Check superclass
        method.declaringClass.superclass?.let { superClass ->
            try {
                val superMethod = superClass.getMethod(name, *parameterTypes)
                if (superMethod.isAnnotationPresent(annotationClass.java)) return true
            } catch (_: NoSuchMethodException) {
                // Method not found in superclass, continue
            }
        }

        return false
    }
}
