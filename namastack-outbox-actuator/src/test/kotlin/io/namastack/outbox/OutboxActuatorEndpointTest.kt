package io.namastack.outbox

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OutboxActuatorEndpointTest {
    private lateinit var outboxRecordRepository: OutboxRecordRepository
    private lateinit var actuatorEndpoint: OutboxActuatorEndpoint

    @BeforeEach
    fun setUp() {
        outboxRecordRepository = mockk(relaxed = true)
        actuatorEndpoint = OutboxActuatorEndpoint(outboxRecordRepository)
    }

    @Test
    fun `deletes outbox records for specific aggregate and status`() {
        actuatorEndpoint.deleteOutboxRecords("test-aggregate", OutboxRecordStatus.COMPLETED)

        verify(exactly = 1) {
            outboxRecordRepository.deleteByRecordKeyAndStatus("test-aggregate", OutboxRecordStatus.COMPLETED)
        }
    }

    @Test
    fun `deletes all outbox records for status`() {
        actuatorEndpoint.deleteAllOutboxRecords(OutboxRecordStatus.FAILED)

        verify(exactly = 1) {
            outboxRecordRepository.deleteByStatus(OutboxRecordStatus.FAILED)
        }
    }
}
