package io.namastack.outbox.handler.method

import io.namastack.outbox.annotation.OutboxRetryable
import io.namastack.outbox.handler.OutboxHandlerRegistry
import io.namastack.outbox.handler.OutboxRetryAware
import io.namastack.outbox.retry.OutboxRetryPolicy
import io.namastack.outbox.retry.OutboxRetryPolicyRegistry
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/**
 * Sealed base class for all outbox handler methods.
 *
 * Represents a handler method with reflection metadata. Subclasses define
 * specific handler signatures:
 * - [TypedHandlerMethod]: Single parameter with specific type
 * - [GenericHandlerMethod]: Two parameters (Any + OutboxRecordMetadata)
 *
 * Each handler method has a unique [id] derived from its class name,
 * method name, and parameter types. This id is used for tracking and routing.
 *
 * @param bean The bean instance containing the handler method
 * @param method The actual Java Method object for reflection
 */
sealed class OutboxHandlerMethod(
    val bean: Any,
    val method: Method,
) {
    /**
     * Unique identifier for this handler method.
     *
     * Format: `className#methodName(paramType1,paramType2,...)`
     *
     * Example: `com.example.OrderHandler#handle(com.example.OrderCreated)`
     */
    val id: String = buildId()

    /**
     * Registers this handler method with the given registry.
     *
     * @param registry The handler registry to register with
     */
    abstract fun register(registry: OutboxHandlerRegistry)

    /**
     * Registers the retry policy for this handler method.
     *
     * Resolves the appropriate retry policy using the cascading strategy:
     * 1. @OutboxRetryable annotation on the method
     * 2. OutboxRetryAware interface implementation on the bean
     * 3. Default retry policy from configuration
     *
     * The resolved policy is registered in the registry using this handler's unique ID.
     *
     * @param retryPolicyRegistry The retry policy registry to register with
     */
    fun registerRetryPolicy(retryPolicyRegistry: OutboxRetryPolicyRegistry) {
        resolveRetryPolicy(retryPolicyRegistry)?.let {
            retryPolicyRegistry.register(id, it)
        }
    }

    /**
     * Builds a unique identifier for this handler method.
     *
     * Combines the bean's fully qualified class name, method name,
     * and all parameter types to create a unique identifier.
     *
     * This id is used to:
     * - Track handler registrations (detect duplicates)
     * - Route records to their handlers via metadata
     * - Enable handler lookup by id
     *
     * @return Unique handler method identifier
     */
    protected fun buildId(): String {
        val className = bean::class.java.name
        val methodName = method.name

        val paramTypes = method.parameterTypes.joinToString(",") { it.name }

        return "$className#$methodName($paramTypes)"
    }

    /**
     * Resolves the retry policy for this handler method.
     *
     * Uses a cascading resolution strategy with the following priority:
     *
     * 1. **@OutboxRetryable annotation**: If present on the method, loads the
     *    retry policy bean by class (if specified) or name (if specified) from Spring context.
     *    Class-based lookup takes precedence over name-based lookup.
     *
     * 2. **OutboxRetryAware interface**: If the bean implements this interface,
     *    uses the policy returned by `getRetryPolicy()`.
     *
     * 3. **Default policy**: Falls back to the default retry policy from configuration.
     *
     * @param registry The retry policy registry providing bean lookup and default policy
     * @return The resolved OutboxRetryPolicy for this handler
     * @throws IllegalStateException if a referenced policy bean is not found
     */
    protected fun resolveRetryPolicy(registry: OutboxRetryPolicyRegistry): OutboxRetryPolicy? {
        AnnotatedElementUtils
            .findMergedAnnotation(
                method,
                OutboxRetryable::class.java,
            )?.let { annotation ->
                if (annotation.value != OutboxRetryPolicy::class) {
                    return registry.getRetryPolicy(annotation.value)
                }
                if (annotation.name.isNotBlank()) {
                    return registry.getRetryPolicy(annotation.name)
                }
            }

        if (bean is OutboxRetryAware) {
            return bean.getRetryPolicy()
        }

        return null
    }
}
