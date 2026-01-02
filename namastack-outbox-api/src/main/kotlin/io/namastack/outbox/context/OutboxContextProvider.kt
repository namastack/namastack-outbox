package io.namastack.outbox.context

/**
 * Provider for contributing context data to outbox records.
 *
 * Context providers are invoked when an outbox record is scheduled,
 * allowing different components to contribute context information such as:
 * - Distributed tracing IDs (trace ID, span ID)
 * - Multi-tenancy information (tenant ID, organization ID)
 * - Correlation IDs for request tracking
 * - User or security context information
 *
 * Multiple providers can be registered and their contributions are merged.
 * In case of key conflicts, the last provider wins (order matters).
 *
 * ## Example - Tracing Context Provider
 *
 * ```kotlin
 * @Component
 * class TracingContextProvider : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return mapOf(
 *             "traceId" to (MDC.get("traceId") ?: ""),
 *             "spanId" to (MDC.get("spanId") ?: "")
 *         ).filterValues { it.isNotEmpty() }
 *     }
 * }
 * ```
 *
 * ## Example - Tenant Context Provider
 *
 * ```kotlin
 * @Component
 * class TenantContextProvider(private val tenantContext: TenantContext) : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return tenantContext.getCurrentTenantId()?.let {
 *             mapOf("tenantId" to it)
 *         } ?: emptyMap()
 *     }
 * }
 * ```
 *
 * @author Aleksander Zamojski
 * @since 1.0.0
 */
interface OutboxContextProvider {
    /**
     * Provides context data to be stored with the outbox record.
     *
     * This method is called once when `outbox.schedule()` is invoked.
     * The returned map is merged with context from other providers.
     *
     * ## Guidelines
     * - Return empty map if no context is available
     * - Use meaningful key names (e.g., "traceId", "tenantId")
     * - Keep values as strings (serialize complex types if needed)
     * - Avoid null values - filter them out before returning
     * - Keep implementation lightweight (called on every schedule)
     *
     * @return Map of context key-value pairs. Never return null, use emptyMap() instead.
     */
    fun provide(): Map<String, String>
}
