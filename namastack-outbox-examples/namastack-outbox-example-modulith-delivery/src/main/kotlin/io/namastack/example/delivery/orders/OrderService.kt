package io.namastack.example.delivery.orders

import io.namastack.example.delivery.orders.internal.CustomerOrder
import io.namastack.example.delivery.orders.internal.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrderService(
    private val orders: OrderRepository,
) {
    @Transactional(rollbackFor = [Exception::class])
    fun placeOrder(
        description: String,
        amountCents: Long,
    ): UUID {
        val order = CustomerOrder.place(description, amountCents)
        return orders.save(order).id
    }
}
