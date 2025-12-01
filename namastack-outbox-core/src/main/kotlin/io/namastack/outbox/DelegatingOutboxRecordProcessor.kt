package io.namastack.outbox

import org.slf4j.LoggerFactory

/**
 * Delegates processing of OutboxRecords to the appropriate processor(s) from the registry.
 * If processorBeanName is set, only the matching processor is invoked. Otherwise, all processors are called.
 *
 * @since 0.4.0
 */
class DelegatingOutboxRecordProcessor(
    private val processorRegistry: OutboxRecordProcessorRegistry,
) : OutboxRecordProcessor {
    private val logger = LoggerFactory.getLogger(DelegatingOutboxRecordProcessor::class.java)

    override fun process(record: OutboxRecord) {
        val processorName = record.processorName
        val processor =
            processorRegistry.getProcessor(processorName)
                ?: throw IllegalArgumentException(
                    "No processor found with name '$processorName' (record ${record.id})",
                )

        logger.debug("Processor $processorName found for record ${record.id}")
        processor.process(record)
    }
}
