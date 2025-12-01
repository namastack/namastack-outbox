package io.namastack.outbox

class OutboxRecordProcessorRegistry(
    private val processors: Map<String, OutboxRecordProcessor>,
) {
    fun getAllProcessors(): Map<String, OutboxRecordProcessor> = processors

    fun getProcessor(beanName: String): OutboxRecordProcessor? = processors[beanName]
}
