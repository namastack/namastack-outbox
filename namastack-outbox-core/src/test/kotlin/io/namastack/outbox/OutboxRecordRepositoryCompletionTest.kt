package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class OutboxRecordRepositoryCompletionTest {
    @Test
    fun `default pages defer queries to their key and bound access`() {
        val repository = mockk<OutboxRecordRepository>()
        val records = List(3) { OutboxRecordTestFactory.outboxRecord(recordKey = "good") }
        every { repository.findIncompleteRecordPages(any(), any()) } answers { callOriginal() }
        every { repository.findIncompleteRecordsByRecordKey("good") } returns records
        every { repository.findIncompleteRecordsByRecordKey("bad") } throws IllegalStateException("unavailable payload")

        val pages = repository.findIncompleteRecordPages(setOf("bad", "good"), 2)
        verify(exactly = 0) { repository.findIncompleteRecordsByRecordKey(any()) }
        assertThat(pages.getValue("good")).hasSize(2)
        assertThat(pages.getValue("good")[0]).isSameAs(records[0])
        assertThat(pages.getValue("good").nextRetryAt(0)).isEqualTo(records[0].nextRetryAt)
        assertThatThrownBy { pages.getValue("good")[2] }.isInstanceOf(IndexOutOfBoundsException::class.java)
        assertThatThrownBy { pages.getValue("bad").size }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { repository.findIncompleteRecordsByRecordKey("good") }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `custom repositories inherit completion through their existing update`(updated: Boolean) {
        val repository = mockk<OutboxRecordRepository>()
        val record = OutboxRecordTestFactory.outboxRecord()
        every { repository.updateCompletion(record) } answers { callOriginal() }
        every { repository.update(record) } returns updated

        assertThat(repository.updateCompletion(record)).isEqualTo(updated)

        verify(exactly = 1) { repository.update(record) }
        verify(exactly = 0) { repository.save(any<OutboxRecord<*>>()) }
    }
}
