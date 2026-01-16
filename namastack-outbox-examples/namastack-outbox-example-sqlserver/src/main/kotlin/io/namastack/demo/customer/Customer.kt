package io.namastack.demo.customer

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import java.util.UUID

@Entity(name = "customer")
data class Customer(
    @Id
    @Column(columnDefinition = "uniqueidentifier")
    val id: UUID,
    @Column(nullable = false)
    val firstname: String,
    @Column(nullable = false)
    val lastname: String,
    @Column(nullable = false)
    val email: String,
) {
    companion object {
        fun register(
            firstname: String,
            lastname: String,
            email: String,
        ): Customer =
            Customer(
                id = UUID.randomUUID(),
                firstname = firstname,
                lastname = lastname,
                email = email,
            )
    }
}
