package io.namastack.example.delivery

import io.namastack.example.delivery.orders.OrderService
import io.namastack.outbox.OutboxProcessingScheduler
import io.namastack.outbox.OutboxRecordRepository
import io.namastack.outbox.handler.invoker.OutboxHandlerInvoker
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit.SECONDS

class DeliveryIdempotencyRestartTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `restart redelivers an unacknowledged record without duplicating committed work`() {
        val orderId =
            startApplication().use { application ->
                application.getBean(OutboxProcessingScheduler::class.java).stop()
                val id = application.getBean(OrderService::class.java).placeOrder("Restart after handler commit", 4990)
                val records = application.getBean(OutboxRecordRepository::class.java)
                val invoiceDelivery = records.findPendingRecords().single { it.handlerId == "billing.issue-invoice" }

                // Commit the business transaction and its idempotency record, then stop before acknowledgement.
                application.getBean(OutboxHandlerInvoker::class.java).dispatch(invoiceDelivery)

                val jdbc = application.getBean(JdbcClient::class.java)
                assertThat(countForOrder(jdbc, "invoice", id)).isEqualTo(1)
                assertThat(records.findPendingRecords().map { it.id }).contains(invoiceDelivery.id)
                assertThat(
                    jdbc
                        .sql("SELECT status FROM idempotency_records WHERE idempotency_key = ?")
                        .param(invoiceDelivery.id)
                        .query(String::class.java)
                        .single(),
                ).isEqualTo("COMPLETE")
                id
            }

        // New Spring proxies, engine, pool and store; only the file database carries state over.
        startApplication().use { application ->
            val jdbc = application.getBean(JdbcClient::class.java)
            val records = application.getBean(OutboxRecordRepository::class.java)
            await().atMost(20, SECONDS).untilAsserted {
                assertThat(records.findCompletedRecords()).hasSize(4)
                assertThat(records.findPendingRecords()).isEmpty()
                assertThat(countForOrder(jdbc, "invoice", orderId)).isEqualTo(1)
                assertThat(countForOrder(jdbc, "ledger_entry", orderId)).isEqualTo(1)
                assertThat(countForOrder(jdbc, "notification", orderId)).isEqualTo(2)
                assertThat(
                    jdbc
                        .sql("SELECT COUNT(*) FROM idempotency_records WHERE status = 'COMPLETE'")
                        .query(Long::class.java)
                        .single(),
                ).isEqualTo(4)
            }
        }
    }

    private fun startApplication(): ConfigurableApplicationContext =
        SpringApplicationBuilder(DeliveryApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--demo.enabled=false",
                "--spring.datasource.url=jdbc:h2:file:${directory.resolve("delivery")};DB_CLOSE_ON_EXIT=FALSE",
                "--namastack.outbox.polling.fixed.interval=100ms",
                "--spring.main.banner-mode=off",
            )

    private fun countForOrder(
        jdbc: JdbcClient,
        table: String,
        orderId: UUID,
    ): Long =
        jdbc
            .sql("SELECT COUNT(*) FROM $table WHERE order_id = ?")
            .param(orderId.toString())
            .query(Long::class.java)
            .single()
}
