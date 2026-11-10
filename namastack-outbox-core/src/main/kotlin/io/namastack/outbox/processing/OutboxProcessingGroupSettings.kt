package io.namastack.outbox.processing

/**
 * Producer settings for processing groups in one outbox.
 *
 * Enable only after all workers reading this outbox support processing groups; worker
 * capabilities are not detected. Stored records keep their group, so drain affected deliveries
 * before changing a handler's group or this setting.
 *
 * @property enabled Whether newly scheduled records use processing groups; disabled by default
 *
 * @author Roland Beisel
 * @since 1.12.0
 */
data class OutboxProcessingGroupSettings(
    val enabled: Boolean = false,
)
