package io.namastack.outbox

import io.mockk.every
import io.mockk.mockk
import io.namastack.outbox.handler.OutboxHandler
import io.namastack.outbox.handler.OutboxHandlerIdentity
import io.namastack.outbox.kafka.KafkaOutboxHandler
import io.namastack.outbox.kafka.kafkaOutboxRouting
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.support.SendResult
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit.SECONDS

/** Real persistence and worker delivery into KafkaOutboxHandler; only the Kafka transport is mocked. */
@OutboxIntegrationTest
@Import(ProcessingGroupMessagingIntegrationTest.MessagingConfiguration::class)
@TestPropertySource(properties = ["namastack.outbox.processing-groups.enabled=false"])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProcessingGroupMessagingIntegrationTest {
    @Autowired
    private lateinit var outbox: Outbox

    @Autowired
    private lateinit var repository: OutboxRecordRepository

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var clock: Clock

    @Autowired
    private lateinit var sent: SentMessages

    @Test
    fun `pending grouped record reaches Kafka with its business key even when group scheduling is disabled`() {
        transactions.executeWithoutResult {
            // Persisted fixture from an earlier deployment: old handler alias and old group assignment.
            repository.save(
                OutboxRecord
                    .Builder<Event>()
                    .key("~ns.pg1~shipping:order:42")
                    .payload(Event("pending"))
                    .handlerId("kafka-v1")
                    .build(clock),
            )
            outbox.schedule(Event("new"), "order:99")
        }

        await().atMost(10, SECONDS).untilAsserted {
            assertThat(repository.findCompletedRecords()).hasSize(2)
            assertThat(sent.records).hasSize(2)
        }
        val pending = sent.records.single { (it.value() as Event).name == "pending" }
        assertThat(pending.topic()).isEqualTo("orders")
        assertThat(pending.key()).isEqualTo("order:42")
        assertThat(
            String(pending.headers().lastHeader("processing-group").value(), Charsets.UTF_8),
        ).isEqualTo("shipping")

        val newlyScheduled = sent.records.single { (it.value() as Event).name == "new" }
        assertThat(newlyScheduled.key()).isEqualTo("order:99")
        assertThat(
            String(newlyScheduled.headers().lastHeader("processing-group").value(), Charsets.UTF_8),
        ).isEqualTo("default")
        assertThat(repository.findCompletedRecords().associate { it.handlerId to it.key })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "kafka-v1" to "~ns.pg1~shipping:order:42",
                    "kafka-v2" to "order:99",
                ),
            )
    }

    data class Event(
        val name: String,
    )

    class SentMessages {
        val records = CopyOnWriteArrayList<ProducerRecord<Any, Any>>()
    }

    @TestConfiguration
    class MessagingConfiguration {
        @Bean
        fun sentMessages() = SentMessages()

        @Bean
        fun kafkaOperations(sent: SentMessages): KafkaOperations<Any, Any> {
            val operations = mockk<KafkaOperations<Any, Any>>()
            every { operations.send(any<ProducerRecord<Any, Any>>()) } answers {
                val record = firstArg<ProducerRecord<Any, Any>>()
                sent.records.add(record)
                CompletableFuture.completedFuture(
                    SendResult(record, RecordMetadata(TopicPartition(record.topic(), 0), 0, 0, 0, 0, 0)),
                )
            }
            return operations
        }

        @Bean
        fun messagingHandler(operations: KafkaOperations<Any, Any>): OutboxHandler {
            val routing =
                kafkaOutboxRouting {
                    defaults {
                        target("orders")
                        filter { payload, _ -> payload is Event }
                        header("processing-group") { _, metadata -> metadata.processingGroup }
                    }
                }
            // Assign a group through the existing interface, delegating delivery to the real integration.
            return object : OutboxHandler by KafkaOutboxHandler(operations, routing) {
                override fun getGenericHandlerIdentity() = OutboxHandlerIdentity("kafka-v2", setOf("kafka-v1"))

                override fun getGenericHandlerProcessingGroup() = "externalization"
            }
        }
    }

    @SpringBootApplication
    class TestApplication
}
