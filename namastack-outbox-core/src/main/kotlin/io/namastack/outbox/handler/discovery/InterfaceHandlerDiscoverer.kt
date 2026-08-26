package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxTypedHandler
import io.namastack.outbox.handler.ReflectionUtils
import org.springframework.util.ClassUtils

/**
 * Discovers primary methods implemented through the public handler interfaces.
 *
 * Interface configuration, including stable IDs, aliases, and generic scheduling support, is
 * copied into the resulting candidates for later assembly.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object InterfaceHandlerDiscoverer {
    /** Returns typed and generic interface candidates declared by [bean]. */
    fun discover(
        bean: Any,
        beanName: String,
    ): List<HandlerCandidate> {
        val lambdaId = beanName.takeIf { ClassUtils.isLambdaClass(ReflectionUtils.getTargetClass(bean)) }
        return buildList {
            if (bean is OutboxTypedHandler<*>) {
                add(
                    HandlerCandidate(
                        beanName = beanName,
                        bean = bean,
                        method = ReflectionUtils.findMethod(bean, "handle", 2),
                        source = HandlerSource.TYPED_INTERFACE,
                        configuredId = bean.getHandlerId(),
                        configuredAliases = bean.getHandlerAliases(),
                        lambdaBeanNameId = lambdaId,
                        supportsPayload = { _, _ -> true },
                    ),
                )
            }
            if (bean is OutboxHandler) {
                add(
                    HandlerCandidate(
                        beanName = beanName,
                        bean = bean,
                        method = ReflectionUtils.findMethod(bean, "handle", 2),
                        source = HandlerSource.GENERIC_INTERFACE,
                        configuredId = bean.getHandlerId(),
                        configuredAliases = bean.getHandlerAliases(),
                        lambdaBeanNameId = lambdaId,
                        supportsPayload = bean::supports,
                    ),
                )
            }
        }
    }
}
