package io.namastack.example.modulith.order

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "orders")
data class Order(
    @Id
    val id: UUID,
    @Column(nullable = false)
    val sku: String,
    @Column(nullable = false)
    val amountCents: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: OrderStatus,
) {
    companion object {
        fun place(
            sku: String,
            amountCents: Long,
        ): Order =
            Order(
                id = UUID.randomUUID(),
                sku = sku,
                amountCents = amountCents,
                status = OrderStatus.PLACED,
            )
    }
}
