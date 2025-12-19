package io.namastack.outbox.context

import org.slf4j.LoggerFactory

/**
 * Collects and merges context from all registered [OutboxContextProvider] implementations.
 *
 * This collector is automatically invoked when an outbox record is scheduled via `outbox.schedule()`.
 * It aggregates context data from multiple providers, allowing different components to contribute
 * metadata (e.g., tracing IDs, tenant information, correlation IDs) to outbox records.
 *
 * ## Merging Behavior
 *
 * - All registered providers are invoked sequentially
 * - Context entries from all providers are merged into a single map
 * - If multiple providers return the same key, the last provider's value wins
 * - Failed providers are logged and skipped (don't break the entire collection process)
 *
 * ## Example Usage with Multiple Providers
 *
 * ```kotlin
 * // Provider 1: Tracing context
 * @Component
 * class TracingContextProvider : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return mapOf(
 *             "traceId" to MDC.get("traceId"),
 *             "spanId" to MDC.get("spanId")
 *         ).filterValues { it != null }
 *     }
 * }
 *
 * // Provider 2: Tenant context
 * @Component
 * class TenantContextProvider(private val tenantContext: TenantContext) : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return tenantContext.getCurrentTenantId()?.let {
 *             mapOf("tenantId" to it)
 *         } ?: emptyMap()
 *     }
 * }
 *
 * // Provider 3: User context
 * @Component
 * class UserContextProvider(private val securityContext: SecurityContext) : OutboxContextProvider {
 *     override fun provide(): Map<String, String> {
 *         return securityContext.getCurrentUserId()?.let {
 *             mapOf("userId" to it, "username" to securityContext.getCurrentUsername())
 *         } ?: emptyMap()
 *     }
 * }
 *
 * // The collector automatically merges all contexts:
 * // Result: { "traceId": "abc123", "spanId": "xyz789", "tenantId": "tenant-1", "userId": "user-42", "username": "john.doe" }
 * ```
 *
 * ## Error Handling
 *
 * If a provider throws an exception, the collector:
 * 1. Logs a warning with the provider class name and error message
 * 2. Skips that provider's context
 * 3. Continues collecting from remaining providers
 *
 * This ensures that one failing provider doesn't prevent the entire outbox scheduling operation.
 *
 * @param providers List of all registered OutboxContextProvider beans
 *
 * @author Aleksander Zamojski
 * @since 0.5.0
 */
class OutboxContextCollector(
    private val providers: List<OutboxContextProvider>,
) {
    private val log = LoggerFactory.getLogger(OutboxContextCollector::class.java)

    /**
     * Collects and merges context from all registered providers.
     *
     * Invokes all [OutboxContextProvider] implementations and aggregates their
     * context data into a single map. This method is called once per `outbox.schedule()`
     * invocation to gather metadata that will be persisted with the outbox record.
     *
     * ## Process Flow
     *
     * 1. Iterate through all registered providers
     * 2. Invoke `provider.provide()` for each provider
     * 3. If provider succeeds: Add its entries to the result
     * 4. If provider fails: Log warning and continue with remaining providers
     * 5. Merge all entries into a single map (last provider wins on key conflicts)
     *
     * ## Example Result
     *
     * Given three providers returning:
     * - Provider 1: `{ "traceId": "abc123", "spanId": "xyz789" }`
     * - Provider 2: `{ "tenantId": "tenant-1" }`
     * - Provider 3: `{ "userId": "user-42" }`
     *
     * Result: `{ "traceId": "abc123", "spanId": "xyz789", "tenantId": "tenant-1", "userId": "user-42" }`
     *
     * @return Merged context map from all providers. Returns empty map if no providers
     *         are registered or all providers fail/return empty maps.
     */
    fun collectContext(): Map<String, String> =
        providers
            .flatMap { provider ->
                try {
                    provider.provide().entries
                } catch (ex: Exception) {
                    log.warn("Context provider {} failed: {}", provider::class.simpleName, ex.message, ex)
                    emptyList()
                }
            }.associate { it.key to it.value }
}
