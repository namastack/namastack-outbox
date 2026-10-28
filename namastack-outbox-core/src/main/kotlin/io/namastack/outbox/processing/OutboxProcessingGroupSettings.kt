package io.namastack.outbox.processing

/**
 * Producer settings for processing groups in one outbox.
 *
 * Enable grouped writes only after all workers reading this outbox support the storage format.
 * This flag does not detect worker capabilities. Drain affected deliveries before changing a
 * handler's group; changing these settings does not move existing records to another group.
 *
 * @property enabled Whether newly scheduled records use processing groups; disabled by default
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
data class OutboxProcessingGroupSettings(
    val enabled: Boolean = false,
)
