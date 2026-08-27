package io.namastack.outbox.retry

import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import kotlin.reflect.KClass

/**
 * Resolves retry policies and provides handler-ID-based compatibility lookup.
 *
 * Handler-specific policies are read from complete registrations in [OutboxHandlerRegistry].
 * Default and bean-based policy resolution remain owned here.
 *
 * Explicit retry policies are resolved during handler registration using:
 * 1. @OutboxRetryable annotation → loads Spring bean by name or class
 * 2. OutboxRetryAware interface → uses policy from handler
 *
 * The default policy is resolved lazily during record processing when neither source provides an
 * explicit policy.
 *
 * @param beanFactory Spring bean factory for loading policy beans by name or class
 * @param handlerRegistry Registry owning the complete handler registrations
 *
 * @author Roland Beisel
 * @since 1.0.0
 */
class OutboxRetryPolicyRegistry internal constructor(
    private val beanFactory: BeanFactory,
    private val handlerRegistry: OutboxHandlerRegistry,
) {
    /**
     * Used as fallback during policy resolution when no specific policy
     * is configured via annotation or interface.
     *
     * Only loaded when first accessed, avoiding eager dependency loading during BeanPostProcessor initialization.
     */
    private val defaultRetryPolicy: OutboxRetryPolicy by lazy {
        beanFactory.getBean<OutboxRetryPolicy>("outboxRetryPolicy")
    }

    /**
     * Retrieves the retry policy for a specific handler method.
     *
     * The explicit policy stored in the complete handler registration is returned when present.
     * Otherwise, the default policy is resolved lazily.
     *
     * @param handlerId Unique identifier of the handler method
     * @return The registered handler policy or the default policy
     */
    fun getByHandlerId(handlerId: String): OutboxRetryPolicy =
        handlerRegistry.getRegistrationById(handlerId)?.explicitRetryPolicy
            ?: defaultRetryPolicy

    /**
     * Retrieves a retry policy bean from the Spring context by name.
     *
     * Used when a handler specifies a policy via @OutboxRetryable annotation with name.
     * If the bean is not found, throws an exception with a helpful message
     * listing all available retry policy beans.
     *
     * @param beanName The Spring bean name of the retry policy
     * @return The loaded OutboxRetryPolicy bean
     * @throws IllegalStateException if the bean is not found or has wrong type
     */
    fun getRetryPolicy(beanName: String): OutboxRetryPolicy =
        try {
            beanFactory.getBean<OutboxRetryPolicy>(beanName)
        } catch (ex: Exception) {
            val available =
                if (beanFactory is ListableBeanFactory) {
                    beanFactory.getBeansOfType<OutboxRetryPolicy>().keys.sorted()
                } else {
                    emptyList()
                }

            throw IllegalStateException(
                "Retry policy bean '$beanName' not found. Available: $available",
                ex,
            )
        }

    /**
     * Retrieves a retry policy bean from the Spring context by type.
     *
     * Used when a handler specifies a policy via @OutboxRetryable annotation with class.
     * If the bean is not found or multiple beans of the same type exist,
     * throws an exception with a helpful message.
     *
     * @param policyClass The retry policy class to look up
     * @return The loaded OutboxRetryPolicy bean
     * @throws IllegalStateException if the bean is not found, multiple beans exist, or has wrong type
     */
    fun getRetryPolicy(policyClass: KClass<out OutboxRetryPolicy>): OutboxRetryPolicy =
        try {
            beanFactory.getBean(policyClass.java)
        } catch (ex: Exception) {
            val available =
                if (beanFactory is ListableBeanFactory) {
                    beanFactory.getBeansOfType<OutboxRetryPolicy>().mapValues { it.value::class.simpleName }
                } else {
                    emptyMap()
                }

            throw IllegalStateException(
                "Retry policy bean of type '${policyClass.simpleName}' not found. " +
                    "Available: ${available.entries.joinToString { "${it.key} (${it.value})" }}",
                ex,
            )
        }
}
