package io.namastack.example.delivery.orders.internal

import io.namastack.example.delivery.orders.OrderPlaced
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.AbstractAggregateRoot
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("CUSTOMER_ORDER")
class CustomerOrder(
    @Id val id: UUID,
    val description: String,
    val amountCents: Long,
    @Version var version: Long? = null,
) : AbstractAggregateRoot<CustomerOrder>() {
    companion object {
        fun place(
            description: String,
            amountCents: Long,
        ): CustomerOrder {
            require(amountCents > 0) { "An order must have a positive amount" }

            val order = CustomerOrder(UUID.randomUUID(), description, amountCents)
            order.registerEvent(OrderPlaced(order.id, description, amountCents))

            return order
        }
    }
}
