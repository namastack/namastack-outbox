package io.namastack.outbox.retry

import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.getBean
import org.springframework.beans.factory.getBeansOfType
import kotlin.reflect.KClass

/**
 * Registry that manages retry policies for outbox handler methods.
 *
 * Stores the resolved retry policy for each handler method and provides
 * lookup capabilities during record processing. Acts as a central repository
 * for all retry policies in the outbox system.
 *
 * Retry policies are resolved during handler registration (startup) using:
 * 1. @OutboxRetryable annotation → loads Spring bean by name or class
 * 2. OutboxRetryAware interface → uses policy from handler
 * 3. Default policy from configuration
 *
 * @param beanFactory Spring bean factory for loading policy beans by name or class
 *
 * @author Roland Beisel
 * @since 0.5.0
 */
class OutboxRetryPolicyRegistry(
    private val beanFactory: BeanFactory,
) {
    /**
     * Map of retry policies indexed by handler method ID.
     * Populated during handler registration at application startup.
     */
    private val policiesById = mutableMapOf<String, OutboxRetryPolicy>()

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
     * Registers a retry policy for a specific handler method.
     *
     * Called during handler registration to store the resolved policy.
     * Each handler method has exactly one retry policy.
     *
     * @param handlerId Unique identifier of the handler method
     * @param policy The retry policy to use for this handler
     */
    fun register(
        handlerId: String,
        policy: OutboxRetryPolicy,
    ) {
        policiesById[handlerId] = policy
    }

    /**
     * Retrieves the retry policy for a specific handler method.
     *
     * Returns the registered policy for the handler, or the default policy
     * if no specific policy was registered (shouldn't happen in practice).
     *
     * @param handlerId Unique identifier of the handler method
     * @return The retry policy for this handler, or default if not found
     */
    fun getByHandlerId(handlerId: String): OutboxRetryPolicy = policiesById[handlerId] ?: defaultRetryPolicy

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
