package io.namastack.outbox

import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit.MILLIS

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(
    JdbcClientAutoConfiguration::class,
    JdbcOutboxAutoConfiguration::class,
    JdbcOutboxSchemaAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@ContextConfiguration(classes = [JdbcOutboxRecordRepositoryTest.TestApplication::class])
@Testcontainers
class JdbcCompletionPostgresIntegrationTest {
    @Autowired
    private lateinit var repository: JdbcOutboxRecordRepository

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `completion preserves stored payload and ordering while persisting failure metadata`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key(
                    "postgres-completion",
                ).payload("original")
                .handlerId("handler")
                .build(Clock.systemUTC())
        repository.create(record)
        val completion = completionOf(record)

        assertThat(repository.updateCompletion(completion)).isTrue()

        val stored = repository.findCompletedRecords().single()
        assertThat(stored.payload).isEqualTo("original")
        assertThat(stored.key).isEqualTo(record.key)
        assertThat(stored.createdAt).isCloseTo(record.createdAt, within(1, MILLIS))
        assertThat(stored.handlerId).isEqualTo(record.handlerId)
        assertThat(stored.completedAt).isEqualTo(completion.completedAt)
        assertThat(stored.failureCount).isEqualTo(2)
        assertThat(stored.failureReason).isEqualTo("fallback succeeded")
        assertThat(stored.nextRetryAt).isEqualTo(completion.nextRetryAt)
        assertThat(repository.findIncompleteRecordsByRecordKey(record.key)).isEmpty()
        jdbcClient.sql("DELETE FROM outbox_record WHERE id = :id").param("id", record.id).update()
        assertThat(repository.updateCompletion(completion)).isFalse()
        assertThat(repository.findCompletedRecords()).isEmpty()
    }

    @Test
    fun `completion participates in rollback`() {
        val record =
            OutboxRecord
                .Builder<String>()
                .key(
                    "postgres-rollback",
                ).payload("original")
                .handlerId("handler")
                .build(Clock.systemUTC())
        repository.create(record)
        val nested = TransactionTemplate(transactionTemplate.transactionManager!!)
        nested.propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        nested.executeWithoutResult { transaction ->
            assertThat(repository.updateCompletion(completionOf(record))).isTrue()
            transaction.setRollbackOnly()
        }
        assertThat(repository.findIncompleteRecordsByRecordKey(record.key).map { it.id }).containsExactly(record.id)
        assertThat(repository.findCompletedRecords()).isEmpty()
    }

    private fun completionOf(record: OutboxRecord<*>): OutboxRecord<Any> =
        OutboxRecord.restore(
            id = record.id,
            recordKey = "ignored-key",
            payload = Any(),
            context = mapOf("ignored" to "value"),
            partition = 255,
            createdAt = Instant.parse("2020-01-01T00:00:00Z"),
            status = OutboxRecordStatus.COMPLETED,
            completedAt = Instant.parse("2026-01-01T00:00:00Z"),
            failureCount = 2,
            failureReason = "fallback succeeded",
            nextRetryAt = Instant.parse("2026-01-01T01:00:00Z"),
            handlerId = "ignored-handler",
            failureException = null,
        )

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { postgres.driverClassName }
        }
    }
}
