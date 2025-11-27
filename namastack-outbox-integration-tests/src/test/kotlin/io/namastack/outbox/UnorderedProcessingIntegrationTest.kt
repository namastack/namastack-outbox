package io.namastack.outbox

import io.namastack.outbox.OrderedProcessingIntegrationTest.TestProcessor
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test

/**
 * Integration test for ordered outbox processing with stop-on-first-failure enabled.
 *
 * Scenario:
 * - Inserts three records for the same aggregate: [failure, success, success]
 * - Verifies that only the first (failure) is processed and the others remain unprocessed.
 */
@DataJpaTest(showSql = false)
@ImportAutoConfiguration(
    OutboxCoreAutoConfiguration::class,
    JpaOutboxAutoConfiguration::class,
    OutboxJacksonAutoConfiguration::class,
)
@Import(TestProcessor::class)
@EnableConfigurationProperties(OutboxProperties::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = ["outbox.processing.stop-on-first-failure=false"])
class UnorderedProcessingIntegrationTest {
    private val clock: Clock = Clock.systemDefaultZone()

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @AfterEach
    fun cleanup() = cleanupTables()

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `should process remaining records in case of a failure`() {
        val aggregateId = "aggregate-1"
        createRecord(aggregateId, "failure")
        createRecord(aggregateId, "success")
        createRecord(aggregateId, "success")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(outboxRecordRepository.findCompletedRecords().size).isEqualTo(2)
                assertThat(outboxRecordRepository.findFailedRecords()).hasSize(1)
            }
    }

    private fun cleanupTables() {
        transactionTemplate.executeNonNull {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity ").executeUpdate()
            entityManager.flush()
        }
    }

    private fun createRecord(
        aggregateId: String,
        payload: String,
    ): OutboxRecord =
        outboxRecordRepository.save(
            OutboxRecord
                .Builder()
                .aggregateId(aggregateId)
                .payload(payload)
                .eventType("eventType")
                .build(clock),
        )

    /**
     * Test processor that throws an exception for records with payload "failure".
     */
    @Component
    class TestProcessor : OutboxRecordProcessor {
        override fun process(record: OutboxRecord) {
            if (record.payload == "failure") {
                throw RuntimeException("failure")
            }
        }
    }

    @EnableOutbox
    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
