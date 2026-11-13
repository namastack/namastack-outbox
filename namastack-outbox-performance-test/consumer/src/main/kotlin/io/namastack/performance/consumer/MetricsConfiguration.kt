package io.namastack.performance.consumer

import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MetricsConfiguration {
    @Bean
    fun disableDatabaseIntensiveOutboxGauges(): MeterFilter =
        MeterFilter.deny { meter ->
            meter.name == "outbox.records" || meter.name == "outbox.instance.records.pending"
        }
}

