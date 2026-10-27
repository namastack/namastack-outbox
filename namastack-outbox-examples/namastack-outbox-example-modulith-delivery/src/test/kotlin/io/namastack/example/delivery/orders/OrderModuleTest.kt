package io.namastack.example.delivery.orders

import io.namastack.example.delivery.orders.internal.CustomerOrder
import io.namastack.example.delivery.orders.internal.OrderRepository
import io.namastack.outbox.Outbox
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.modulith.test.ApplicationModuleTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Transactional

@ApplicationModuleTest
@TestPropertySource(
    properties = [
        "demo.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:orders-module;DB_CLOSE_DELAY=-1",
    ],
)
class OrderModuleTest {
    @Autowired
    private lateinit var orders: OrderService

    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var repository: OrderRepository

    @MockitoBean
    private lateinit var outbox: Outbox

    @Test
    fun `orders module schedules its public event without loading consumers`() {
        val orderId = orders.placeOrder("Module test", 1590)
        verify(outbox).schedule(OrderPlaced(orderId, "Module test", 1590), "order:$orderId", emptyMap())
        assertThat(
            jdbc
                .sql("SELECT amount_cents FROM customer_order WHERE id = ?")
                .param(orderId.toString())
                .query(Long::class.java)
                .single(),
        ).isEqualTo(1590)
    }

    @Test
    @Transactional
    fun `saving an aggregate again does not republish its cleared domain events`() {
        val order = CustomerOrder.place("Saved twice", 2490)

        repository.save(order)
        repository.save(order)
        val reloaded = repository.findById(order.id).orElseThrow()
        repository.save(reloaded)

        // Neither saving the same instance again nor saving a reloaded aggregate republishes the event.
        verify(outbox).schedule(
            OrderPlaced(order.id, "Saved twice", 2490),
            "order:${order.id}",
            emptyMap(),
        )
    }
}
