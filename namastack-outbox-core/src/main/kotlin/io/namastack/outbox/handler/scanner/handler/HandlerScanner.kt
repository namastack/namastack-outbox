package io.namastack.outbox.handler.scanner.handler

import io.namastack.outbox.handler.scanner.HandlerScanResult

/**
 * Interface for scanning beans and discovering handler methods with their fallbacks.
 *
 * Different implementations handle different discovery strategies:
 * - [AnnotatedHandlerScanner]: Finds methods with @OutboxHandler annotation
 * - [InterfaceHandlerScanner]: Finds beans implementing OutboxHandler or OutboxTypedHandler interfaces
 *
 * Each scanner is responsible for discovering BOTH the handler and its associated fallback (if present).
 * This ensures correct pairing during registration.
 *
 * Scanners are used by the BeanPostProcessor to discover handlers from beans
 * as they are instantiated by Spring.
 *
 * @author Roland Beisel
 * @since 0.4.0
 */
interface HandlerScanner {
    /**
     * Scans a bean for handler methods and their associated fallbacks.
     *
     * Discovers handler methods using the scanner's strategy (annotation-based,
     * interface-based, etc.) and returns them as HandlerScanResult instances containing
     * both the handler and its optional fallback.
     *
     * @param bean The bean to scan for handlers
     * @return List of discovered handler scan results (empty if no handlers found)
     */
    fun scan(bean: Any): List<HandlerScanResult>
}
