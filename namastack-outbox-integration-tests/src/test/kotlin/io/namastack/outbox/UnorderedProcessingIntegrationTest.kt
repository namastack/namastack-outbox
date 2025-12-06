package io.namastack.outbox

import io.namastack.outbox.OrderedProcessingIntegrationTest.TestProcessor
import io.namastack.outbox.annotation.EnableOutbox
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.stereotype.Component
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test

/**
 * Integration test for unordered outbox processing with stop-on-first-failure disabled.
 *
 * Scenario:
 * - Inserts three records for the same record key: [failure, success, success]
 * - Verifies that all records are processed despite the first one failing.
 */
@DataJpaTest(showSql = false)
@DirtiesContext
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

    private fun cleanupTables() {
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM OutboxRecordEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxInstanceEntity").executeUpdate()
            entityManager.createQuery("DELETE FROM OutboxPartitionAssignmentEntity ").executeUpdate()
            entityManager.flush()
            entityManager.clear()
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `should process remaining records in case of a failure`() {
        val recordKey = "record-key-1"
        createRecord(recordKey, "failure")
        createRecord(recordKey, "success")
        createRecord(recordKey, "success")

        await()
            .atMost(10, SECONDS)
            .untilAsserted {
                assertThat(outboxRecordRepository.findCompletedRecords().size).isEqualTo(2)
                assertThat(outboxRecordRepository.findFailedRecords()).hasSize(1)
            }
    }

    // ...existing code...

    private fun createRecord(
        recordKey: String,
        payload: String,
    ): OutboxRecord<String> =
        outboxRecordRepository.save(
            OutboxRecord
                .Builder<String>()
                .key(recordKey)
                .payload(payload)
                .handlerId(
                    @Suppress("ktlint:standard:max-line-length")
                    $$"io.namastack.outbox.OrderedProcessingIntegrationTest$TestProcessor#handle(java.lang.Object,io.namastack.outbox.handler.OutboxRecordMetadata)",
                ).build(clock),
        )

    /**
     * Test processor that throws an exception for records with payload "failure".
     */
    @Component
    class TestProcessor : OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            if ((payload as String) == "failure") {
                throw RuntimeException("failure")
            }
        }
    }

    @EnableOutbox
    @EnableScheduling
    @SpringBootApplication
    class TestApplication
}
