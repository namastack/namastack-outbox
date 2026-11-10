package io.namastack.outbox

import io.namastack.outbox.annotation.OutboxHandler
import io.namastack.outbox.partition.PartitionHasher
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

/** Verifies compatibility-failure handling through real scheduling and record processing. */
@OutboxIntegrationTest
@Import(CompatibilityFailureIntegrationTest.CompatibleHandler::class)
@TestPropertySource(
    properties = [
        "namastack.outbox.polling.fixed.interval=100ms",
        "namastack.outbox.instance.rebalance-interval=100ms",
        "namastack.outbox.processing.stop-on-first-failure=false",
    ],
)
class CompatibilityFailureIntegrationTest {
    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    private lateinit var jdbcClient: JdbcClient

    @BeforeEach
    fun clearInvocations() {
        invocations.clear()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `compatibility failures remain pending while other keys in the current batch finish`() {
        val createdAt = Instant.now().minusSeconds(1)

        persistRecords(
            record(
                id = UNKNOWN_PAYLOAD_RECORD_ID,
                key = UNKNOWN_PAYLOAD_KEY,
                recordType = "example.payload.UnavailablePayload",
                payload = "{}",
                handlerId = COMPATIBLE_HANDLER_ID,
                createdAt = createdAt,
            ),
            record(
                id = PAYLOAD_SUCCESSOR_RECORD_ID,
                key = UNKNOWN_PAYLOAD_KEY,
                payload = """{"value":"payload-successor"}""",
                handlerId = COMPATIBLE_HANDLER_ID,
                createdAt = createdAt.plusMillis(1),
            ),
            record(
                id = UNKNOWN_HANDLER_RECORD_ID,
                key = UNKNOWN_HANDLER_KEY,
                payload = """{"value":"unknown-handler"}""",
                handlerId = "unavailable-handler",
                createdAt = createdAt,
            ),
            record(
                id = HANDLER_SUCCESSOR_RECORD_ID,
                key = UNKNOWN_HANDLER_KEY,
                payload = """{"value":"handler-successor"}""",
                handlerId = COMPATIBLE_HANDLER_ID,
                createdAt = createdAt.plusMillis(1),
            ),
            record(
                id = COMPATIBLE_RECORD_ID,
                key = COMPATIBLE_KEY,
                payload = """{"value":"compatible"}""",
                handlerId = COMPATIBLE_HANDLER_ID,
                createdAt = createdAt,
            ),
        )

        await().atMost(15, SECONDS).untilAsserted {
            assertExpectedDatabaseState()
        }

        await().during(2, SECONDS).atMost(5, SECONDS).untilAsserted {
            assertExpectedDatabaseState()
        }
    }

    private fun assertExpectedDatabaseState() {
        val records = loadRecords()
        val incompatibleRecordIds =
            setOf(
                UNKNOWN_PAYLOAD_RECORD_ID,
                PAYLOAD_SUCCESSOR_RECORD_ID,
                UNKNOWN_HANDLER_RECORD_ID,
                HANDLER_SUCCESSOR_RECORD_ID,
            )

        assertThat(records.keys).containsExactlyInAnyOrderElementsOf(incompatibleRecordIds + COMPATIBLE_RECORD_ID)
        assertThat(records.getValue(COMPATIBLE_RECORD_ID).status).isEqualTo(OutboxRecordStatus.COMPLETED)
        assertThat(invocations).containsExactly("compatible")

        incompatibleRecordIds
            .map(records::getValue)
            .forEach { record ->
                assertThat(record.status).isEqualTo(OutboxRecordStatus.NEW)
                assertThat(record.failureCount).isZero()
                assertThat(record.failureReason).isNull()
                assertThat(record.completedAt).isNull()
                assertThat(record.nextRetryAt).isEqualTo(record.createdAt)
            }

        val unknownPayloadRecord = records.getValue(UNKNOWN_PAYLOAD_RECORD_ID)
        assertThat(unknownPayloadRecord.recordType).isEqualTo("example.payload.UnavailablePayload")
        assertThat(unknownPayloadRecord.payload).isEqualTo("{}")
        assertThat(unknownPayloadRecord.handlerId).isEqualTo(COMPATIBLE_HANDLER_ID)

        val payloadSuccessorRecord = records.getValue(PAYLOAD_SUCCESSOR_RECORD_ID)
        assertThat(payloadSuccessorRecord.recordType).isEqualTo(CompatiblePayload::class.java.name)
        assertThat(payloadSuccessorRecord.payload).isEqualTo("""{"value":"payload-successor"}""")
        assertThat(payloadSuccessorRecord.handlerId).isEqualTo(COMPATIBLE_HANDLER_ID)

        assertThat(records.getValue(UNKNOWN_HANDLER_RECORD_ID).handlerId).isEqualTo("unavailable-handler")
        assertThat(records.getValue(HANDLER_SUCCESSOR_RECORD_ID).payload)
            .isEqualTo("""{"value":"handler-successor"}""")
    }

    private fun persistRecords(vararg records: JdbcOutboxRecordEntity) {
        transactionTemplate.executeWithoutResult {
            records.forEach { record ->
                jdbcClient
                    .sql(
                        """
                        INSERT INTO outbox_record
                        (id, status, record_key, record_type, payload, context, partition_no, created_at,
                         completed_at, failure_count, failure_reason, next_retry_at, handler_id)
                        VALUES (:id, :status, :key, :type, :payload, NULL, :partition, :created,
                                NULL, 0, NULL, :created, :handler)
                        """.trimIndent(),
                    ).param("id", record.id)
                    .param("status", record.status.name)
                    .param("key", record.recordKey)
                    .param("type", record.recordType)
                    .param("payload", record.payload)
                    .param("partition", record.partitionNo)
                    .param("created", java.sql.Timestamp.from(record.createdAt))
                    .param("handler", record.handlerId)
                    .update()
            }
        }
    }

    private fun loadRecords(): Map<String, JdbcOutboxRecordEntity> =
        transactionTemplate.execute {
            jdbcClient
                .sql("SELECT * FROM outbox_record")
                .query(JdbcOutboxRecordEntityRowMapper())
                .list()
                .associateBy(JdbcOutboxRecordEntity::id)
        }

    private fun record(
        id: String,
        key: String,
        payload: String,
        handlerId: String,
        createdAt: Instant,
        recordType: String = CompatiblePayload::class.java.name,
    ) = JdbcOutboxRecordEntity(
        id = id,
        status = OutboxRecordStatus.NEW,
        recordKey = key,
        recordType = recordType,
        payload = payload,
        context = null,
        partitionNo = PartitionHasher.getPartitionForRecordKey(key),
        createdAt = createdAt,
        completedAt = null,
        failureCount = 0,
        failureReason = null,
        nextRetryAt = createdAt,
        handlerId = handlerId,
    )

    data class CompatiblePayload(
        val value: String,
    )

    @Component
    class CompatibleHandler {
        @OutboxHandler(id = COMPATIBLE_HANDLER_ID)
        fun handle(payload: CompatiblePayload) {
            invocations.add(payload.value)
        }
    }

    @SpringBootApplication
    class TestApplication

    companion object {
        private const val UNKNOWN_PAYLOAD_RECORD_ID = "unknown-payload-record"
        private const val PAYLOAD_SUCCESSOR_RECORD_ID = "payload-successor-record"
        private const val UNKNOWN_HANDLER_RECORD_ID = "unknown-handler-record"
        private const val HANDLER_SUCCESSOR_RECORD_ID = "handler-successor-record"
        private const val COMPATIBLE_RECORD_ID = "compatible-record"
        private const val UNKNOWN_PAYLOAD_KEY = "unknown-payload-key"
        private const val UNKNOWN_HANDLER_KEY = "unknown-handler-key"
        private const val COMPATIBLE_KEY = "compatible-key"
        private const val COMPATIBLE_HANDLER_ID = "compatible-handler"

        private val invocations = CopyOnWriteArrayList<String>()
    }
}
