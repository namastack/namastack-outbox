package io.namastack.springoutbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.springoutbox.OutboxRecordStatus.COMPLETED
import io.namastack.springoutbox.OutboxRecordStatus.FAILED
import io.namastack.springoutbox.OutboxRecordStatus.NEW

class OutboxMetricsMeterBinder(
    private val outboxRecordStatusRepository: OutboxRecordStatusRepository,
) : MeterBinder {
    override fun bindTo(meterRegistry: MeterRegistry) {
        val statuses = listOf(NEW, FAILED, COMPLETED)

        statuses.forEach { status ->
            Gauge
                .builder("outbox.records.count") { outboxRecordStatusRepository.countByStatus(status) }
                .description("Count of outbox records by status")
                .tag("status", status.name.lowercase())
                .register(meterRegistry)
        }
    }
}
