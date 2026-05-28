package io.namastack.outbox.event

import org.slf4j.LoggerFactory

/**
 * Registry that maps stable logical event type names and their aliases to Java classes.
 *
 * Populated at startup by [OutboxEventTypeRegistrar] from `@OutboxEvent(name=…, aliases=[…])`
 * annotations.  Used by the entity mappers to write logical names to `record_type` and to
 * resolve them back on read, with a classloader-fallback for legacy FQCN rows.
 */
class OutboxEventTypeRegistry {
    private val log = LoggerFactory.getLogger(OutboxEventTypeRegistry::class.java)

    private val nameToClass: MutableMap<String, Class<*>> = LinkedHashMap()
    private val classToName: MutableMap<Class<*>, String> = LinkedHashMap()

    internal fun register(
        logicalName: String,
        clazz: Class<*>,
        aliases: List<String>,
    ) {
        putOrThrow(logicalName, clazz)
        classToName[clazz] = logicalName

        for (alias in aliases) {
            val existing = nameToClass[alias]
            if (existing != null && existing != clazz) {
                log.warn(
                    "OutboxEventTypeRegistry: alias '{}' registered for '{}' already maps to '{}' — " +
                        "this may cause silent data hijack on legacy rows",
                    alias,
                    clazz.name,
                    existing.name,
                )
            }
            putOrThrow(alias, clazz)
        }
    }

    private fun putOrThrow(
        key: String,
        clazz: Class<*>,
    ) {
        val previous = nameToClass.putIfAbsent(key, clazz)
        check(previous == null || previous == clazz) {
            "OutboxEventTypeRegistry: duplicate logical name '$key' — " +
                "registered for both '${previous!!.name}' and '${clazz.name}'"
        }
    }

    /** Returns the logical name for [clazz], or `null` if none was registered. */
    fun findLogicalName(clazz: Class<*>): String? = classToName[clazz]

    /**
     * Returns the class for [recordType], or `null` if it is not a known logical name or alias.
     * The caller should fall back to `ClassLoader.loadClass(recordType)` for legacy FQCN rows.
     */
    fun resolveClass(recordType: String): Class<*>? = nameToClass[recordType]
}
