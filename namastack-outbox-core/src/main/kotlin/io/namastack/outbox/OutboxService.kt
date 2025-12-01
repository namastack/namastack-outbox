package io.namastack.outbox

import java.time.Clock

/**
 * Outbox service for scheduling outbox records for processing by all registered processors.
 *
 * This class uses the OutboxRecordProcessorRegistry to discover all available processors and
 * creates a separate OutboxRecord for each processor, setting the processorBeanName accordingly.
 * Each record is then persisted via the OutboxRecordRepository.
 *
 * This approach ensures that every processor receives its own record and supports flexible,
 * multi-processor event handling and migration scenarios.
 *
 * @since 0.4.0
 */
class OutboxService(
    private val recordRepository: OutboxRecordRepository,
    private val recordProcessorRegistry: OutboxRecordProcessorRegistry,
    private val clock: Clock,
) : Outbox {
    /**
     * Schedules the given outbox record for all registered processors.
     *
     * For each processor in the registry, a copy of the original record is created with the
     * processorName set to the processor's bean name. Each record is then saved individually.
     *
     * @param outboxRecord The original outbox record to be scheduled
     */
    override fun schedule(outboxRecord: OutboxRecord) {
        recordProcessorRegistry.getAllProcessors().forEach { (processorName, _) ->
            val record =
                OutboxRecord
                    .Builder()
                    .recordKey(outboxRecord.recordKey)
                    .recordType(outboxRecord.recordType)
                    .payload(outboxRecord.payload)
                    .processorName(processorName)
                    .build(clock)

            recordRepository.save(record)
        }
    }
}
