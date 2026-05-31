package io.namastack.outbox.handler.method

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.annotation.OutboxHandlerId
import io.namastack.outbox.event.LogicalNameValidator
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
) {
    /** FQCN-based identifier: `ClassName#methodName(Type1,Type2,...)` using the un-proxied target class. */
    val fqcnId: String = buildFqcnId()

    /**
     * Primary identifier used for routing and persistence.
     * Returns the stable logical ID if one was declared via `@OutboxHandler(name=…)` /
     * `@OutboxHandler("…")` or `@OutboxHandlerId(value=…)`; otherwise falls back to [fqcnId].
     */
    val id: String

    /**
     * Legacy identifier using the bean's runtime class name, which may include
     * CGLIB proxy suffixes like `$$SpringCGLIB$$0`.
     */
    val legacyId: String = buildLegacyId()

    /** Explicit aliases declared on the handler annotation, used for backward-compat dispatch. */
    val explicitAliases: List<String>

    init {
        val (logical, aliases) = readAnnotation()
        if (logical != null) {
            LogicalNameValidator.validate(logical, "handler '${method.declaringClass.name}#${method.name}'")
        }
        aliases.forEach { alias ->
            LogicalNameValidator.validate(alias, "handler '${method.declaringClass.name}#${method.name}' alias")
        }
        id = logical ?: fqcnId
        explicitAliases = aliases
    }

    private fun readAnnotation(): Pair<String?, List<String>> {
        val onMethod = method.getAnnotation(OutboxHandler::class.java)
        if (onMethod != null) {
            require(onMethod.name.isBlank() || onMethod.value.isBlank()) {
                "@OutboxHandler on ${method.declaringClass.name}#${method.name}: " +
                    "use either 'name' or 'value', not both"
            }
            val logicalId = (onMethod.name.ifBlank { onMethod.value }).trim().takeIf { it.isNotEmpty() }
            return logicalId to onMethod.aliases.map { it.trim() }.filter { it.isNotEmpty() }
        }
        val onClass = ReflectionUtils.getTargetClass(bean).getAnnotation(OutboxHandlerId::class.java)
        if (onClass != null) {
            val logicalId = onClass.value.trim().takeIf { it.isNotEmpty() }
            return logicalId to onClass.aliases.map { it.trim() }.filter { it.isNotEmpty() }
        }
        return null to emptyList()
    }

    protected fun buildFqcnId(): String {
        val className = ReflectionUtils.getTargetClass(bean).name
        val methodName = method.name
        val paramTypes = method.parameterTypes.joinToString(",") { it.name }
        return "$className#$methodName($paramTypes)"
    }

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
