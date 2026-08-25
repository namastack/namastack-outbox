package io.namastack.outbox.handler.method

import org.springframework.aop.support.AopUtils
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Owns reflected invocation and consistently unwraps invocation exceptions.
 *
 * Routing identity belongs to primary handler methods, not this common invocation base.
 *
 * @param bean bean that owns the handler method
 * @param method reflected method invoked by subclasses
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
abstract class InvocableHandlerMethod(
    val bean: Any,
    val method: Method,
) {
    private val invocableMethod: Method =
        if (AopUtils.isAopProxy(bean)) AopUtils.selectInvocableMethod(method, bean::class.java) else method

    /** Invokes the underlying method and rethrows the handler's original exception. */
    protected fun invokeMethod(vararg args: Any?) {
        try {
            if (!invocableMethod.canAccess(bean)) invocableMethod.trySetAccessible()
            invocableMethod.invoke(bean, *args)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException
        }
    }
}
