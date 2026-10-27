package io.namastack.example.delivery

import io.namastack.example.delivery.billing.InvoiceIssued
import io.namastack.example.delivery.orders.OrderPlaced
import io.namastack.example.delivery.orders.OrderService
import io.namastack.outbox.OutboxRecordRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit.SECONDS

@SpringBootTest(
    properties = [
        "demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:delivery;DB_CLOSE_DELAY=-1",
        "namastack.outbox.polling.fixed.interval=100ms",
    ],
)
@Import(DeliveryIntegrationTest.ListenerConfiguration::class)
class DeliveryIntegrationTest {
    @Autowired
    private lateinit var orders: OrderService

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var records: OutboxRecordRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var publisher: ApplicationEventPublisher

    @Autowired
    private lateinit var listeners: SpringListenerProbe

    @Test
    fun `delivers to every handler and delivers the follow-up event`() {
        val orderId = orders.placeOrder("Modular applications", 4990)

        await().atMost(15, SECONDS).untilAsserted {
            assertThat(countForOrder("invoice", orderId)).isEqualTo(1)
            assertThat(countForOrder("ledger_entry", orderId)).isEqualTo(1)
            assertThat(notificationKinds(orderId)).containsExactlyInAnyOrder("ORDER_RECEIVED", "INVOICE_ISSUED")

            val completed = records.findCompletedRecords().filter { it.key.endsWith("order:$orderId") }
            assertThat(completed.map { it.handlerId }).containsExactlyInAnyOrder(
                "billing.issue-invoice",
                "billing.record-ledger",
                "notifications.order-received",
                "notifications.invoice-issued",
            )
            assertThat(completed.map { it.key }).containsOnly(
                "~ns.pg1~billing:order:$orderId",
                "~ns.pg1~notifications:order:$orderId",
            )
        }

        // Ordinary Spring events still work, but annotated business events use only the outbox.
        publisher.publishEvent(OrdinarySpringEvent(orderId))
        assertThat(listeners.ordinaryEvents).contains(orderId)
        assertThat(listeners.domainEvents).doesNotContain(orderId)
    }

    @Test
    fun `commits the order and all three initial deliveries together`() {
        val orderId =
            TransactionTemplate(transactionManager).execute {
                val id = orders.placeOrder("Transactional scheduling", 2990)

                // These records are visible to this producer transaction before it commits.
                assertThat(records.findPendingRecords().filter { it.key.endsWith("order:$id") }).hasSize(3)
                assertThat(countForOrder("invoice", id)).isZero()
                assertThat(countForOrder("notification", id)).isZero()
                id
            }

        await().atMost(15, SECONDS).untilAsserted {
            assertThat(notificationKinds(orderId)).containsExactlyInAnyOrder("ORDER_RECEIVED", "INVOICE_ISSUED")
        }
    }

    @Test
    fun `producer rollback removes business data and every delivery`() {
        var orderId: UUID? = null

        assertThatThrownBy {
            TransactionTemplate(transactionManager).executeWithoutResult {
                orderId = orders.placeOrder("Rolled-back order", 1990)
                error("Simulated producer failure")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        val id = requireNotNull(orderId)
        assertThat(
            jdbc
                .sql("SELECT COUNT(*) FROM customer_order WHERE id = ?")
                .param(id.toString())
                .query(Long::class.java)
                .single(),
        ).isZero()
        assertThat(
            jdbc
                .sql("SELECT COUNT(*) FROM outbox_record WHERE record_key LIKE ?")
                .param("%order:$id")
                .query(Long::class.java)
                .single(),
        ).isZero()
        assertThat(countForOrder("invoice", id)).isZero()
        assertThat(countForOrder("ledger_entry", id)).isZero()
        assertThat(countForOrder("notification", id)).isZero()
    }

    private fun countForOrder(
        table: String,
        orderId: UUID,
    ): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE order_id = ?")
            .param(orderId.toString())
            .query(Long::class.java)
            .single()

    private fun notificationKinds(orderId: UUID): List<String> =
        jdbc
            .sql("SELECT kind FROM notification WHERE order_id = ?")
            .param(orderId.toString())
            .query(String::class.java)
            .list()
            .filterNotNull()

    data class OrdinarySpringEvent(
        val orderId: UUID,
    )

    class SpringListenerProbe {
        val ordinaryEvents: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
        val domainEvents: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        @EventListener
        fun onOrdinaryEvent(event: OrdinarySpringEvent) {
            ordinaryEvents += event.orderId
        }

        @EventListener
        fun onOrderPlaced(event: OrderPlaced) {
            domainEvents += event.orderId
        }

        @EventListener
        fun onInvoiceIssued(event: InvoiceIssued) {
            domainEvents += event.orderId
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    class ListenerConfiguration {
        @Bean
        fun springListenerProbe() = SpringListenerProbe()
    }
}
