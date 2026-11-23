package io.namastack.demo

import io.namastack.outbox.Outbox
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.handler.OutboxTypedHandler
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    classes = [ExampleRetryTest.RetryTestConfiguration::class],
    properties = ["spring.datasource.url=jdbc:h2:mem:retry-test"],
)
class ExampleRetryTest {
    @Test
    fun `retries the same outbox record after a transient handler failure`(
        @Autowired outbox: Outbox,
        @Autowired transactions: TransactionTemplate,
        @Autowired handler: FailOnceHandler,
    ) {
        val event = RetryEvent(UUID.randomUUID())
        transactions.executeWithoutResult { outbox.schedule(event) }

        await().atMost(20, SECONDS).untilAsserted {
            assertThat(handler.processedEvent).isEqualTo(event)
        }

        val attempts = handler.attempts.toList()
        assertThat(attempts).hasSize(2)
        val (first, retry) = attempts
        assertThat(first.recordId).isNotBlank()
        assertThat(retry.recordId).isEqualTo(first.recordId)
        assertThat(attempts.map { it.attempt }).containsExactly(1, 2)
        assertThat(attempts.map { it.isRetry }).containsExactly(false, true)
    }

    @EnableAutoConfiguration
    class RetryTestConfiguration {
        @Bean
        fun failOnceHandler() = FailOnceHandler()
    }

    data class RetryEvent(val id: UUID)

    class FailOnceHandler : OutboxTypedHandler<RetryEvent> {
        val attempts = CopyOnWriteArrayList<OutboxRecordMetadata>()

        @Volatile
        var processedEvent: RetryEvent? = null
            private set

        override fun handle(
            payload: RetryEvent,
            metadata: OutboxRecordMetadata,
        ) {
            attempts.add(metadata)
            if (attempts.size == 1) {
                error("Simulated transient handler failure")
            }
            processedEvent = payload
        }
    }
}
