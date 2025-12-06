package io.namastack.outbox.handler.scanner

import io.namastack.outbox.handler.method.OutboxHandlerMethod

/**
 * Interface for scanning beans and discovering handler methods.
 *
 * Different implementations handle different discovery strategies:
 * - [AnnotatedHandlerScanner]: Finds methods with @OutboxHandler annotation
 * - [InterfaceHandlerScanner]: Finds beans implementing OutboxHandler or OutboxTypedHandler interfaces
 *
 * Scanners are used by the BeanPostProcessor to discover handlers from beans
 * as they are instantiated by Spring.
 */
interface HandlerScanner {
    /**
     * Scans a bean for handler methods.
     *
     * Discovers handler methods using the scanner's strategy (annotation-based,
     * interface-based, etc.) and returns them as OutboxHandlerMethod instances.
     *
     * @param bean The bean to scan for handlers
     * @return List of discovered handler methods (empty if no handlers found)
     */
    fun scan(bean: Any): List<OutboxHandlerMethod>
}
