package io.namastack.example.modulith.payment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "payments")
data class Payment(
    @Id
    val id: UUID,
    @Column(nullable = false)
    val orderId: UUID,
    @Column(nullable = false)
    val amountCents: Long,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PaymentStatus,
    var providerReference: String? = null,
) {
    companion object {
        fun request(
            orderId: UUID,
            amountCents: Long,
        ): Payment =
            Payment(
                id = UUID.randomUUID(),
                orderId = orderId,
                amountCents = amountCents,
                status = PaymentStatus.REQUESTED,
            )
    }

    fun capture(providerReference: String) {
        this.providerReference = providerReference
        this.status = PaymentStatus.CAPTURED
    }
}
