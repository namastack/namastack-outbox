package io.namastack.demo.customer

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import org.springframework.data.domain.AbstractAggregateRoot
import java.util.UUID

@Entity(name = "customer")
data class Customer(
    @Id
    val id: UUID,
    @Column(nullable = false)
    val firstname: String,
    @Column(nullable = false)
    val lastname: String,
    @Column(nullable = false)
    val email: String,
) : AbstractAggregateRoot<Customer>() {
    companion object {
        fun register(
            firstname: String,
            lastname: String,
            email: String,
        ): Customer {
            val customer =
                Customer(
                    id = UUID.randomUUID(),
                    firstname = firstname,
                    lastname = lastname,
                    email = email,
                )

            customer.registerEvent(
                CustomerRegisteredEvent(
                    id = customer.id,
                    firstname = customer.firstname,
                    lastname = customer.lastname,
                    email = customer.email,
                ),
            )

            return customer
        }
    }

    fun deactivate() {
        registerEvent(CustomerDeactivatedEvent(id))
    }
}
