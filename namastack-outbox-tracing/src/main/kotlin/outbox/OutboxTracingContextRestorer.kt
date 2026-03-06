package io.namastack.outbox

import io.micrometer.observation.ObservationRegistry
import io.namastack.outbox.observability.OutboxObservationDocumentation
import io.namastack.outbox.observability.OutboxProcessObservationContext

class OutboxTracingContextRestorer(
    private val observationRegistry: ObservationRegistry,
) {
    fun invoke(
        record: OutboxRecord<*>,
        block: () -> Any,
    ): Any {
        val observation =
            OutboxObservationDocumentation.OUTBOX_RECORD_PROCESS.observation(
                null,
                OutboxObservationDocumentation.DefaultOutboxProcessObservationConvention.INSTANCE,
                { OutboxProcessObservationContext(record, "handler") },
                observationRegistry,
            )

        return observation.observe(block)
    }
}
