package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

class OutboxServiceTest {
    private lateinit var repository: OutboxRecordRepository
    private lateinit var registry: OutboxRecordProcessorRegistry
    private lateinit var service: OutboxService

    private val savedRecords = mutableListOf<OutboxRecord>()
    private val clock: Clock = Clock.systemUTC()

    class DummyProcessor : OutboxRecordProcessor {
        override fun process(record: OutboxRecord) {}
    }

    @BeforeEach
    fun setup() {
        repository =
            mockk {
                every { save(any()) } answers {
                    val record = firstArg<OutboxRecord>()
                    savedRecords.add(record)
                    record
                }
            }
        registry =
            OutboxRecordProcessorRegistry(
                mapOf(
                    "procA" to DummyProcessor(),
                    "procB" to DummyProcessor(),
                ),
            )
        service = OutboxService(repository, registry, clock)
        savedRecords.clear()
    }

    @Test
    fun `schedule should save one record per processor`() {
        val record =
            OutboxRecord(
                id = UUID.randomUUID().toString(),
                recordKey = "key1",
                recordType = "typeA",
                payload = "payloadA",
                partition = 1,
                createdAt = OffsetDateTime.now(clock),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = OffsetDateTime.now(clock),
                processorName = "dummy",
            )
        service.schedule(record)
        assertThat(savedRecords).hasSize(2)
        assertThat(savedRecords.map { it.processorName }).containsExactlyInAnyOrder("procA", "procB")
    }

    @Test
    fun `schedule should save multiple records per schedule call`() {
        val record1 =
            OutboxRecord(
                id = UUID.randomUUID().toString(),
                recordKey = "key1",
                recordType = "typeA",
                payload = "payloadA",
                partition = 1,
                createdAt = OffsetDateTime.now(clock),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = OffsetDateTime.now(clock),
                processorName = "dummy",
            )
        val record2 =
            OutboxRecord(
                id = UUID.randomUUID().toString(),
                recordKey = "key2",
                recordType = "typeB",
                payload = "payloadB",
                partition = 2,
                createdAt = OffsetDateTime.now(clock),
                status = OutboxRecordStatus.NEW,
                completedAt = null,
                retryCount = 0,
                nextRetryAt = OffsetDateTime.now(clock),
                processorName = "dummy",
            )

        service.schedule(record1)
        service.schedule(record2)

        assertThat(savedRecords).hasSize(4)
        assertThat(savedRecords.map { it.processorName }).containsExactlyInAnyOrder("procA", "procB", "procA", "procB")
    }
}
