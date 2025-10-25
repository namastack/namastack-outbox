package io.namastack.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import io.namastack.outbox.OutboxRecordStatus.COMPLETED
import io.namastack.outbox.OutboxRecordStatus.FAILED
import io.namastack.outbox.OutboxRecordStatus.NEW

/**
 * Micrometer meter binder for outbox record metrics.
 *
 * This binder registers gauges for tracking the count of outbox records
 * by their status (NEW, FAILED, COMPLETED).
 *
 * @param outboxRecordStatusRepository Repository for querying record counts
 *
 * @author Roland Beisel
 * @since 0.1.0
 */
class OutboxMetricsMeterBinder(
    private val outboxRecordStatusRepository: OutboxRecordStatusRepository,
) : MeterBinder {
    /**
     * Binds outbox record metrics to the provided meter registry.
     *
     * Creates gauges for each outbox record status to track record counts.
     *
     * @param meterRegistry The meter registry to bind metrics to
     */
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
