package io.namastack.outbox.event

/**
 * Centralises `record_type` write and read logic for all entity mappers.
 *
 * **Write** — returns the logical name registered in [OutboxEventTypeRegistry] for the
 * payload class if one is configured, otherwise falls back to the Java FQCN.
 *
 * **Read** — resolves a stored `record_type` string back to a [Class]: checks the registry
 * first (covers logical names and aliases), then falls back to the context classloader for
 * legacy FQCN rows.
 */
class OutboxRecordTypeResolver(
    private val registry: OutboxEventTypeRegistry,
) {
    fun toRecordType(payload: Any): String = registry.findLogicalName(payload.javaClass) ?: payload.javaClass.name

    fun resolveClass(recordType: String): Class<*> =
        registry.resolveClass(recordType)
            ?: try {
                Thread.currentThread().contextClassLoader.loadClass(recordType)
            } catch (ex: ClassNotFoundException) {
                throw IllegalStateException("Cannot find class for record type $recordType", ex)
            }
}
