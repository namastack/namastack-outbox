package io.namastack.outbox.handler.discovery

/**
 * Coordinates annotation- and interface-based handler discovery for a Spring bean.
 *
 * Discovery only collects declarations. Signature and relationship validation happen during
 * registration assembly.
 *
 * @author Roland Beisel
 * @since 1.8.1
 */
internal object HandlerDiscovery {
    /** Discovers all primary and fallback declarations owned by [bean]. */
    fun discover(
        bean: Any,
        beanName: String,
    ) = HandlerDeclarations(
        handlers =
            AnnotatedHandlerDiscoverer.discover(bean, beanName) +
                InterfaceHandlerDiscoverer.discover(bean, beanName),
        fallbacks =
            AnnotatedFallbackDiscoverer.discover(bean) +
                InterfaceFallbackDiscoverer.discover(bean),
    )
}
