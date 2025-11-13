package io.namastack.performance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories

@EnableR2dbcRepositories
@SpringBootApplication(scanBasePackages = ["io.namastack.performance"])
class OutboxPerformanceTestProducerApplication

fun main(args: Array<String>) {
    runApplication<OutboxPerformanceTestProducerApplication>(*args)
}
