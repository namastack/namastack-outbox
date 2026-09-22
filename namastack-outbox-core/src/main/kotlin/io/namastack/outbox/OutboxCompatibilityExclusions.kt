package io.namastack.outbox

/**
 * Thread-safe, bounded collection of identifiers that cannot be processed by the current instance.
 *
 * Repositories use these exclusions to omit complete record keys containing a record that this
 * instance cannot materialize or dispatch. The exclusions are local to the scheduler instance and
 * are never persisted with an outbox record.
 *
 * @param unavailablePayloadTypes Payload types initially known to be unavailable
 * @param unavailableHandlerIds Handler IDs initially known to be unavailable
 * @param unavailableRecordKeys Record keys containing records that cannot be deserialized
 * @author Roland Beisel
 * @since 1.10.0
 */
class OutboxCompatibilityExclusions(
    unavailablePayloadTypes: Set<String> = emptySet(),
    unavailableHandlerIds: Set<String> = emptySet(),
    unavailableRecordKeys: Set<String> = emptySet(),
) {
    companion object {
        /**
         * Maximum number of identifiers retained by an instance for each kind.
         */
        const val MAXIMUM_IDENTIFIERS_PER_KIND = 256
    }

    private val payloadTypes = BoundedSet(unavailablePayloadTypes)
    private val handlerIds = BoundedSet(unavailableHandlerIds)
    private val recordKeys = BoundedSet(unavailableRecordKeys)

    /**
     * Snapshot of payload types that are unavailable on the current instance.
     */
    val unavailablePayloadTypes: Set<String>
        get() = payloadTypes.snapshot()

    /**
     * Snapshot of handler IDs that are unavailable on the current instance.
     */
    val unavailableHandlerIds: Set<String>
        get() = handlerIds.snapshot()

    /**
     * Snapshot of record keys containing a record that cannot be deserialized on this instance.
     */
    val unavailableRecordKeys: Set<String>
        get() = recordKeys.snapshot()

    /**
     * Whether this collection contains no compatibility exclusions.
     */
    val isEmpty: Boolean
        get() = payloadTypes.isEmpty() && handlerIds.isEmpty() && recordKeys.isEmpty()

    /**
     * Adds a payload type that is unavailable on the current instance.
     *
     * @param payloadType Fully qualified name of the unavailable payload type
     * @return `true` if the payload type was newly added, or `false` if it was already present
     */
    fun addUnavailablePayloadType(payloadType: String): Boolean = payloadTypes.add(payloadType)

    /**
     * Adds a handler ID that is unavailable on the current instance.
     *
     * @param handlerId ID of the unavailable handler
     * @return `true` if the handler ID was newly added, or `false` if it was already present
     */
    fun addUnavailableHandlerId(handlerId: String): Boolean = handlerIds.add(handlerId)

    /**
     * Adds a record key containing a record that cannot be deserialized on this instance.
     *
     * @param recordKey Key containing the incompatible record
     * @return `true` if the record key was newly added, or `false` if it was already present
     */
    fun addUnavailableRecordKey(recordKey: String): Boolean = recordKeys.add(recordKey)

    private class BoundedSet(
        initialValues: Set<String>,
    ) {
        private val values = LinkedHashSet<String>()

        init {
            initialValues.forEach(::add)
        }

        @Synchronized
        fun add(value: String): Boolean {
            if (!values.add(value)) return false
            if (values.size > MAXIMUM_IDENTIFIERS_PER_KIND) values.remove(values.first())
            return true
        }

        @Synchronized
        fun snapshot(): Set<String> = values.toSet()

        @Synchronized
        fun isEmpty(): Boolean = values.isEmpty()
    }
}
