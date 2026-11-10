package io.namastack.outbox

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicInteger

class LazyOutboxRecordPageTest {
    @Test
    fun `page size and object inspection never decode records`() {
        val decoded = AtomicInteger()
        val page =
            LazyOutboxRecordPage(
                rows = listOf("future"),
                decode = {
                    decoded.incrementAndGet()
                    error("unavailable payload")
                },
            )
        assertThat(page.size).isEqualTo(1)
        assertThat(page.isEmpty()).isFalse()
        assertThat(page.toString()).contains("LazyOutboxRecordPage")
        assertThat(page).isEqualTo(page)
        page.hashCode()
        assertThat(decoded.get()).isZero()
        assertThatThrownBy { page[0] }.isInstanceOf(IllegalStateException::class.java)
        assertThat(decoded.get()).isEqualTo(1)
    }

    @Test
    fun `repeated access preserves identity and processing mutations`() {
        val decoded = AtomicInteger()
        val record = OutboxRecordTestFactory.outboxRecord(recordKey = "key")
        val page =
            LazyOutboxRecordPage(listOf(record)) {
                decoded.incrementAndGet()
                it
            }
        assertThat(page[0]).isSameAs(record)
        record.failureCount = 2
        assertThat(page[0].failureCount).isEqualTo(2)
        assertThat(page[0]).isSameAs(record)
        assertThat(decoded.get()).isEqualTo(1)
    }

    @Test
    fun `concurrent decoders return the same published record`() {
        val barrier = CyclicBarrier(2)
        val decoded = AtomicInteger()
        val page =
            LazyOutboxRecordPage(listOf("row")) {
                decoded.incrementAndGet()
                barrier.await(5, SECONDS)
                OutboxRecordTestFactory.outboxRecord(recordKey = "key")
            }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val first = workers.submit<OutboxRecord<*>> { page[0] }
            val second = workers.submit<OutboxRecord<*>> { page[0] }
            val record = first.get(5, SECONDS)
            assertThat(second.get(5, SECONDS)).isSameAs(record)
            assertThat(page[0]).isSameAs(record)
            assertThat(decoded.get()).isEqualTo(2)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `failed decoding leaves other records independently readable`() {
        val page =
            LazyOutboxRecordPage(listOf("good", "bad", "later")) {
                check(it != "bad") { "malformed payload" }
                OutboxRecordTestFactory.outboxRecord(recordKey = it)
            }
        val first = page[0]
        assertThatThrownBy { page[1] }.isInstanceOf(IllegalStateException::class.java)
        assertThat(page[0]).isSameAs(first)
        assertThat(page[2].key).isEqualTo("later")
    }

    @Test
    fun `empty page and invalid indexes never call the decoder`() {
        val page = LazyOutboxRecordPage(emptyList<String>()) { error("must not decode") }
        assertThat(page.isEmpty()).isTrue()
        assertThatThrownBy { page[0] }.isInstanceOf(IndexOutOfBoundsException::class.java)
    }
}
