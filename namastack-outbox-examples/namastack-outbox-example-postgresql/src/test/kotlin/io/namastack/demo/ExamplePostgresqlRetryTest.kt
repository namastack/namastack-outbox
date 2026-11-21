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
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(classes = [ExamplePostgresqlRetryTest.RetryTestConfiguration::class])
@Testcontainers
// Close Spring while the database is still running.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExamplePostgresqlRetryTest {
    companion object {
        @Container
        @JvmStatic
        private val postgres = ExamplePostgresqlContainer()

        @JvmStatic
        @DynamicPropertySource
        fun registerDataSource(registry: DynamicPropertyRegistry) = postgres.registerDataSource(registry)
    }

    @Test
    fun `retries the same outbox record after a transient failure`(
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

    // Load the JDBC starter with only the deterministic retry handler.
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
