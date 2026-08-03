package io.namastack.outbox.handler.method

import io.namastack.outbox.annotation.OutboxHandler as OutboxHandlerAnnotation
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.method.internal.ReflectionUtils
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Base class for all handler method wrappers providing common ID generation and invocation logic.
 *
 * Eliminates duplication between regular and fallback handler hierarchies.
 *
 * @param bean The bean instance containing the handler method
 * @param method The Java Method object for reflection
 * @author Roland Beisel
 * @since 1.0.0
 */
abstract class BaseHandlerMethod(
    val bean: Any,
    val method: Method,
) {
    /**
     * Unique identifier for routing and tracking.
     * Uses an explicit annotation or provider ID when available, otherwise the format
     * `ClassName#methodName(Type1,Type2,...)`.
     */
    val id: String = buildId()

    /**
     * Resolves an explicit stable ID before falling back to the generated method ID.
     */
    protected fun buildId(): String {
        val annotatedId =
            AnnotatedElementUtils
                .findMergedAnnotation(method, OutboxHandlerAnnotation::class.java)
                ?.id
                ?.takeIf(String::isNotEmpty)

        if (annotatedId != null) return validateId(annotatedId)

        val providedId = getProvidedId()

        return providedId?.let(::validateId) ?: buildGeneratedId()
    }

    /** Returns the ID supplied by an interface-based handler, when configured. */
    private fun getProvidedId(): String? =
        when (bean) {
            is OutboxHandler -> bean.getHandlerId()
            is OutboxTypedHandler<*> -> bean.getHandlerId()
            else -> null
        }

    /** Validates an explicitly configured handler ID. */
    private fun validateId(id: String): String =
        id.also {
            require(it.isNotBlank()) { "Outbox handler ID must not be blank" }
        }

    /** Builds the default stable ID from the target class and handler method. */
    private fun buildGeneratedId(): String {
        val className = ReflectionUtils.getTargetClass(bean).name
        return buildMethodId(className)
    }

    /**
     * Legacy identifier using the bean's runtime class name, which may include
     * CGLIB proxy suffixes like `$$SpringCGLIB$$0`.
     */
    val legacyId: String = buildLegacyId()

    /**
     * Builds a legacy handler ID using the bean's runtime class name.
     */
    protected fun buildLegacyId(): String {
        val className = bean::class.java.name
        return buildMethodId(className)
    }

    /** Builds the canonical method identifier for the supplied class name. */
    private fun buildMethodId(className: String): String {
        val methodName = method.name
        val paramTypes = method.parameterTypes.joinToString(",") { it.name }

        return "$className#$methodName($paramTypes)"
    }

    /**
     * Invokes handler method via reflection, unwrapping InvocationTargetException
     * to expose the actual exception for retry policies and error handlers.
     *
     * @param args Arguments matching the method signature
     * @throws Throwable Original exception from handler method
     */
    protected fun invokeMethod(vararg args: Any?) {
        try {
            if (!method.canAccess(bean)) {
                method.trySetAccessible()
            }
            method.invoke(bean, *args)
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
    }
}
