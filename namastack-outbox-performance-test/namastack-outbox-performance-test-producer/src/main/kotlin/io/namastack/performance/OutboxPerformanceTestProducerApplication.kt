package io.namastack.performance

import io.namastack.outbox.EnableOutbox
import io.namastack.outbox.OutboxCoreAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@EnableOutbox
@SpringBootApplication(
    exclude = [OutboxCoreAutoConfiguration::class],
)
class OutboxPerformanceTestProducerApplication

fun main(args: Array<String>) {
    runApplication<OutboxPerformanceTestProducerApplication>(*args)
}
