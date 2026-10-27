package io.namastack.outbox.scheduling

import io.namastack.outbox.Outbox
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxRecordMetadata
import io.namastack.outbox.scheduling.AbstractJdbcConcurrentSchedulingTest.MyHandler
import io.namastack.outbox.scheduling.AbstractJdbcConcurrentSchedulingTest.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Component
import org.springframework.test.annotation.DirtiesContext
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.BrokenBarrierException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@DirtiesContext
@TestInstance(PER_CLASS)
@SpringBootTest(
    classes = [TestApplication::class],
    properties = [
        "namastack.outbox.jdbc.schema-initialization.enabled=true",
        "namastack.outbox.polling.fixed.interval=300",
    ],
)
@Import(MyHandler::class)
abstract class AbstractJdbcConcurrentSchedulingTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var outboxRecordRepository: OutboxRecordRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    @Test
    fun `concurrent scheduling in open transactions does not deadlock`() {
        val keys = List(CONCURRENT_TRANSACTIONS) { UUID.randomUUID().toString() }
        val start = CyclicBarrier(CONCURRENT_TRANSACTIONS)
        val scheduled = CyclicBarrier(CONCURRENT_TRANSACTIONS)
        val executor = Executors.newFixedThreadPool(CONCURRENT_TRANSACTIONS)

        try {
            val results =
                keys.map { key ->
                    executor.submit {
                        transactionTemplate.executeWithoutResult {
                            start.await(10, TimeUnit.SECONDS)
                            outbox.schedule("hello", key)
                            // keep all transactions open until every record is written
                            scheduled.await(10, TimeUnit.SECONDS)
                        }
                    }
                }

            val failures =
                results.mapNotNull { result ->
                    runCatching { result.get(60, TimeUnit.SECONDS) }
                        .exceptionOrNull()
                        ?.let { (it as? ExecutionException)?.cause ?: it }
                }

            // a failed transaction breaks the barrier for the others, so report the root failure first
            assertThat(failures.filterNot { it is BrokenBarrierException || it is TimeoutException }).isEmpty()
            assertThat(failures).isEmpty()
        } finally {
            executor.shutdownNow()
        }

        await().atMost(5, TimeUnit.SECONDS).untilAsserted {
            assertThat(outboxRecordRepository.findCompletedRecords().map { it.key }).containsAll(keys)
        }
    }

    @SpringBootApplication
    class TestApplication

    companion object {
        private const val CONCURRENT_TRANSACTIONS = 8
    }

    @Component
    class MyHandler : OutboxHandler {
        override fun handle(
            payload: Any,
            metadata: OutboxRecordMetadata,
        ) {
            // no-op; only needed to mark a record as processed
        }
    }
}
