package io.namastack.outbox.schema

import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.OutboxRecordStatus.NEW
import io.namastack.outbox.config.JdbcOutboxAutoConfiguration
import io.namastack.outbox.config.JdbcOutboxSchemaAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.temporal.ChronoUnit.SECONDS
import java.util.UUID

@DirtiesContext
@TestInstance(PER_CLASS)
@SpringBootTest(
    classes = [AbstractJpaSchemaValidationIntegrationTest.TestApplication::class],
    properties = ["spring.jpa.hibernate.ddl-auto=validate"],
)
abstract class AbstractJpaSchemaValidationIntegrationTest {
    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `JPA entity validates successfully against shipped DDL schema`() {
        // Successful context startup with ddl-auto=validate is the assertion.
        // A SchemaManagementException on startup would fail this test before reaching here.
    }

    @Test
    fun `record age queries run against shipped schema`() {
        val createdAt = Instant.now().truncatedTo(SECONDS).minusSeconds(60)
        val nextRetryAt = createdAt.plusSeconds(3600)
        transactionTemplate.executeWithoutResult {
            outboxRecordRepository.save(
                OutboxRecord.restore(
                    id = UUID.randomUUID().toString(),
                    recordKey = UUID.randomUUID().toString(),
                    payload = "payload",
                    context = emptyMap(),
                    partition = AGE_QUERY_PARTITION,
                    createdAt = createdAt,
                    status = NEW,
                    completedAt = null,
                    failureCount = 0,
                    failureReason = null,
                    nextRetryAt = nextRetryAt,
                    handlerId = "handlerId",
                    failureException = null,
                ),
            )
        }

        assertThat(outboxRecordRepository.findOldestCreatedAtByPartitions(setOf(AGE_QUERY_PARTITION), NEW))
            .isEqualTo(createdAt)
        listOf(true, false).forEach { ignoreRecordKeysWithPreviousFailure ->
            assertThat(
                outboxRecordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = setOf(AGE_QUERY_PARTITION),
                    status = NEW,
                    now = nextRetryAt,
                    ignoreRecordKeysWithPreviousFailure = ignoreRecordKeysWithPreviousFailure,
                ),
            ).isEqualTo(nextRetryAt)
            assertThat(
                outboxRecordRepository.findOldestDueNextRetryAtByPartitions(
                    partitions = setOf(AGE_QUERY_PARTITION),
                    status = NEW,
                    now = nextRetryAt.minusSeconds(1),
                    ignoreRecordKeysWithPreviousFailure = ignoreRecordKeysWithPreviousFailure,
                ),
            ).isNull()
        }
    }

    @SpringBootApplication(exclude = [JdbcOutboxAutoConfiguration::class, JdbcOutboxSchemaAutoConfiguration::class])
    class TestApplication

    companion object {
        private const val AGE_QUERY_PARTITION = 42
    }
}
