package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class OutboxRecordRepositoryTest {
    private val repository = mockk<OutboxRecordRepository>()

    @Test
    fun `default batch count supports repositories implementing only single partition counts`() {
        every { repository.countRecordsByPartitions(any(), any()) } answers { callOriginal() }
        every { repository.countRecordsByPartition(1, NEW) } returns 4L
        every { repository.countRecordsByPartition(2, NEW) } returns 6L

        assertThat(repository.countRecordsByPartitions(setOf(1, 2), NEW)).isEqualTo(10)

        verify(exactly = 1) { repository.countRecordsByPartition(1, NEW) }
        verify(exactly = 1) { repository.countRecordsByPartition(2, NEW) }
    }

    @Test
    fun `default batch count does not query empty partitions`() {
        every { repository.countRecordsByPartitions(any(), any()) } answers { callOriginal() }

        assertThat(repository.countRecordsByPartitions(emptySet(), NEW)).isZero()

        verify(exactly = 0) { repository.countRecordsByPartition(any(), any()) }
    }

    @Test
    fun `default oldest created at lookup is unsupported`() {
        every { repository.findOldestCreatedAtByPartitions(any(), any()) } answers { callOriginal() }

        assertThatThrownBy { repository.findOldestCreatedAtByPartitions(setOf(1), NEW) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `default oldest due next retry at lookup is unsupported`() {
        every { repository.findOldestDueNextRetryAtByPartitions(any(), any(), any(), any()) } answers {
            callOriginal()
        }

        assertThatThrownBy { repository.findOldestDueNextRetryAtByPartitions(setOf(1), NEW, Instant.now(), true) }
            .isInstanceOf(UnsupportedOperationException::class.java)
    }
}
