package io.namastack.performance.consumer

import io.namastack.outbox.handler.registry.OutboxHandlerRegistry
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

@SpringBootApplication
class OutboxPerformanceConsumerApplication {
    @Bean
    fun handlerIdValidator(handlerRegistry: OutboxHandlerRegistry) =
        ApplicationRunner {
            check(handlerRegistry.getHandlerById(PaymentRequestedEventHandler.HANDLER_ID) != null) {
                "Expected performance-test handler is not registered: ${PaymentRequestedEventHandler.HANDLER_ID}"
            }
        }
}

fun main(args: Array<String>) {
    runApplication<OutboxPerformanceConsumerApplication>(*args)
}

