package io.namastack.outbox.handler.method

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
 * @since 0.6.0
 */
abstract class BaseHandlerMethod(
    val bean: Any,
    val method: Method,
) {
    /**
     * Unique identifier for routing and tracking.
     * Format: `ClassName#methodName(Type1,Type2,...)`
     */
    val id: String = buildId()

    /**
     * Builds unique ID from class name, method name, and parameter types.
     * ID remains stable across restarts for persistent record association.
     */
    protected fun buildId(): String {
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
            method.invoke(bean, *args)
        } catch (ex: InvocationTargetException) {
            throw ex.targetException
        }
    }
}
