package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxHandlerWithFallback
import io.namastack.outbox.handler.OutboxTypedHandlerWithFallback
import io.namastack.outbox.handler.ReflectionUtils

/**
 * Discovers fallback methods implemented through the public handler interfaces.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object InterfaceFallbackDiscoverer {
    /** Returns typed and generic interface fallback candidates declared by [bean]. */
    fun discover(bean: Any): List<FallbackCandidate> =
        buildList {
            if (bean is OutboxTypedHandlerWithFallback<*>) {
                add(
                    FallbackCandidate(
                        bean,
                        ReflectionUtils.findMethod(bean, "handleFailure", 2),
                        HandlerSource.TYPED_INTERFACE,
                    ),
                )
            }
            if (bean is OutboxHandlerWithFallback) {
                add(
                    FallbackCandidate(
                        bean,
                        ReflectionUtils.findMethod(bean, "handleFailure", 2),
                        HandlerSource.GENERIC_INTERFACE,
                    ),
                )
            }
        }
}
