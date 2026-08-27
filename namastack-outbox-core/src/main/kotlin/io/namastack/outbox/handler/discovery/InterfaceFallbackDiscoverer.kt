package io.namastack.outbox.handler.discovery

import io.namastack.outbox.handler.OutboxFailureContext
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
    /**
     * Discovers fallback declarations implemented through the handler interfaces.
     *
     * The concrete implementation method and generic payload type are resolved against the bean's
     * user class so overloaded annotated methods cannot be mistaken for interface implementations.
     *
     * @param bean Bean to inspect for typed and generic fallback interfaces
     * @return Unvalidated interface-based fallback declarations
     */
    fun discover(bean: Any): List<FallbackCandidate> =
        buildList {
            if (bean is OutboxTypedHandlerWithFallback<*>) {
                val payloadType =
                    ReflectionUtils.resolveInterfacePayloadType(bean, OutboxTypedHandlerWithFallback::class.java)
                add(
                    FallbackCandidate(
                        bean = bean,
                        method =
                            ReflectionUtils.findInterfaceMethod(
                                bean = bean,
                                handlerInterface = OutboxTypedHandlerWithFallback::class.java,
                                methodName = "handleFailure",
                                contextType = OutboxFailureContext::class.java,
                            ),
                        payloadType = payloadType,
                        source = HandlerSource.TYPED_INTERFACE,
                    ),
                )
            }
            if (bean is OutboxHandlerWithFallback) {
                add(
                    FallbackCandidate(
                        bean = bean,
                        method =
                            ReflectionUtils.findInterfaceMethod(
                                bean = bean,
                                handlerInterface = OutboxHandlerWithFallback::class.java,
                                methodName = "handleFailure",
                                contextType = OutboxFailureContext::class.java,
                            ),
                        payloadType = Any::class.java,
                        source = HandlerSource.GENERIC_INTERFACE,
                    ),
                )
            }
        }
}
