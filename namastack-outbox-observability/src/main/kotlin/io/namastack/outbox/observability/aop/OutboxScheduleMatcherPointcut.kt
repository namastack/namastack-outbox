package io.namastack.outbox.observability.aop

import io.namastack.outbox.Outbox
import org.springframework.aop.ClassFilter
import org.springframework.aop.support.StaticMethodMatcherPointcut
import java.lang.reflect.Method

/**
 * Spring AOP pointcut that matches `schedule(...)` methods on [Outbox] implementations.
 *
 * @author Roland Beisel
 * @since 1.7.0
 */
internal class OutboxScheduleMatcherPointcut : StaticMethodMatcherPointcut() {
    private val classFilter = ClassFilter { Outbox::class.java.isAssignableFrom(it) }

    /**
     * Determines if the given method matches the pointcut.
     *
     * Returns `true` for any method named `schedule`.
     *
     * @param method The candidate method.
     * @param targetClass The class that declares the method.
     * @return `true` if the method matches, `false` otherwise.
     */
    override fun matches(
        method: Method,
        targetClass: Class<*>,
    ): Boolean = method.name == "schedule"

    /**
     * Returns a [ClassFilter] that restricts matching to [Outbox] implementations only.
     *
     * @return the [ClassFilter] for this pointcut
     */
    override fun getClassFilter(): ClassFilter = classFilter
}
