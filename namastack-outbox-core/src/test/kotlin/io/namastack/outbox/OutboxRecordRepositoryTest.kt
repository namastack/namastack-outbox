package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.namastack.outbox.OutboxRecordStatus.NEW
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

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
}
