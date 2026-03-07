package io.namastack.outbox.aop

import io.namastack.outbox.OutboxRecord
import org.springframework.aop.ClassFilter
import org.springframework.aop.support.StaticMethodMatcherPointcut
import java.lang.reflect.Method

internal class OutboxInvokerMatcherPointcut(
    private val invokerClass: Class<*>,
) : StaticMethodMatcherPointcut() {
    private val classFilter = ClassFilter { it == invokerClass }

    override fun matches(
        method: Method,
        targetClass: Class<*>,
    ): Boolean {
        if (method.name != "dispatch") return false

        val params = method.parameterTypes
        return params.size == 1 && params[0] == OutboxRecord::class.java
    }

    override fun getClassFilter(): ClassFilter = classFilter
}
