package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutboxRecordPageTest {
    @Test
    fun `default pages defer each per-key read and isolate loading failures`() {
        val repository = mockk<OutboxRecordRepository>()
        val record = OutboxRecordTestFactory.outboxRecord(recordKey = "good")
        every { repository.findIncompleteRecordPages(any()) } answers { callOriginal() }
        every { repository.findIncompleteRecordsByRecordKey("good") } returns listOf(record)
        every { repository.findIncompleteRecordsByRecordKey("empty") } returns emptyList()
        every { repository.findIncompleteRecordsByRecordKey("bad") } throws IllegalStateException("decode failed")

        val pages = repository.findIncompleteRecordPages(setOf("bad", "good", "empty"))

        verify(exactly = 0) { repository.findIncompleteRecordsByRecordKey(any()) }
        assertThat(pages.keys).containsExactlyInAnyOrder("bad", "good", "empty")
        assertThat(pages.getValue("good").recordId(0)).isEqualTo(record.id)
        assertThat(pages.getValue("good").nextRetryAt(0)).isEqualTo(record.nextRetryAt)
        assertThat(pages.getValue("good")[0]).isSameAs(record)
        record.failureCount = 2
        assertThat(pages.getValue("good")[0].failureCount).isEqualTo(2)
        assertThat(pages.getValue("empty")).isEmpty()
        assertThatThrownBy { pages.getValue("bad").size }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { repository.findIncompleteRecordsByRecordKey("good") }
        assertThat(repository.findIncompleteRecordPages(emptySet())).isEmpty()
    }
}
