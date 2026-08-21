package io.namastack.outbox.handler.method

import io.namastack.outbox.handler.method.internal.ReflectionUtils
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
    id: String? = null,
) {
    /**
     * Unique identifier for routing and tracking. Defaults to
     * `ClassName#methodName(Type1,Type2,...)`; interface-based lambda handlers use their
     * stable Spring bean name instead.
     */
    val id: String = id ?: buildId()

    /**
     * Builds the default ID from class name, method name, and parameter types.
     */
    protected fun buildId(): String {
        val className = ReflectionUtils.getTargetClass(bean).name
        val methodName = method.name
        val paramTypes = method.parameterTypes.joinToString(",") { it.name }

        return "$className#$methodName($paramTypes)"
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
