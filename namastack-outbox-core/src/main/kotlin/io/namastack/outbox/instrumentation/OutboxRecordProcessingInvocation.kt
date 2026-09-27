package io.namastack.outbox.instrumentation

import io.namastack.outbox.OutboxRecord

/**
 * Describes one attempt to process a fully materialized outbox record.
 *
 * @param record Record entering the processor chain.
 * @param channel Logical name of the outbox runtime.
 *
 * @author Aleksander Zamojski
 * @since 1.10.0
 */
data class OutboxRecordProcessingInvocation(
    val record: OutboxRecord<*>,
    val channel: String,
)
