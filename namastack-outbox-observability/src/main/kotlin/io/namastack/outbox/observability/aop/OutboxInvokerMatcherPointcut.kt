package io.namastack.outbox.observability.aop

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.handler.invoker.OutboxFallbackHandlerInvoker
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import org.springframework.aop.ClassFilter
import org.springframework.aop.support.StaticMethodMatcherPointcut
import java.lang.reflect.Method

/**
 * Spring AOP pointcut that matches the `dispatch(OutboxRecord<*>)` method on a specific invoker
 * class.
 *
 * Used together with [OutboxInvokerObservationAdvice] to intercept handler or fallback-handler
 * dispatches. Two separate advisors are registered — one for [OutboxHandlerInvoker] (primary)
 * and one for [OutboxFallbackHandlerInvoker] (fallback).
 *
 * @param invokerClass The exact invoker class whose `dispatch` method should be intercepted.
 *
 * @author Aleksander Zamojski
 * @since 1.7.0
 */
internal class OutboxInvokerMatcherPointcut(
    private val invokerClass: Class<*>,
) : StaticMethodMatcherPointcut() {
    private val classFilter = ClassFilter { it == invokerClass }

    /**
     * Determines if the given method matches the pointcut.
     *
     * Returns `true` only for a method named `dispatch` that accepts a single [OutboxRecord] parameter.
     *
     * @param method The candidate method.
     * @param targetClass The class that declares the method.
     * @return `true` if the method matches, `false` otherwise.
     */
    override fun matches(
        method: Method,
        targetClass: Class<*>,
    ): Boolean {
        if (method.name != "dispatch") return false

        val params = method.parameterTypes
        return params.size == 1 && params[0] == OutboxRecord::class.java
    }

    /**
     * Returns a [ClassFilter] that restricts matching to [invokerClass] only, preventing
     * the advice from being applied to unrelated beans.
     *
     * @return the [ClassFilter] for this pointcut
     */
    override fun getClassFilter(): ClassFilter = classFilter
}
