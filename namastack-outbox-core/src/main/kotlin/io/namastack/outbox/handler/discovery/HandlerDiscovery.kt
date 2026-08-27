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
    /**
     * Discovers annotation- and interface-based declarations owned by a bean.
     *
     * @param bean Bean to inspect for handler and fallback declarations
     * @param beanName Spring name of the inspected bean
     * @return Unvalidated primary and fallback declarations discovered on the bean
     */
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
