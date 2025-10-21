package io.namastack.outbox

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnBean(annotation = [EnableOutbox::class])
internal class OutboxMetricsAutoConfiguration {
    @Bean
    fun outboxMetricsMeterBinder(
        outboxRecordStatusRepository: ObjectProvider<OutboxRecordStatusRepository>,
    ): OutboxMetricsMeterBinder {
        val statusRepository =
            outboxRecordStatusRepository.getIfAvailable()
                ?: throw IllegalStateException(
                    "OutboxRecordStatusRepository bean is missing! The Outbox metrics cannot be registered because no persistence module (e.g. namastack-outbox-jpa) is included. Please add a persistence module to your dependencies to enable Outbox metrics.",
                )

        return OutboxMetricsMeterBinder(statusRepository)
    }
}
