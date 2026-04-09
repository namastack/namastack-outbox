package io.namastack.performance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class OutboxPerformanceTestProcessorApplication

fun main(args: Array<String>) {
    runApplication<OutboxPerformanceTestProcessorApplication>(*args)
}
