package io.namastack.demo.customer

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "customer")
data class Customer(
    @Id
    val id: String,
    val firstname: String,
    val lastname: String,
    private var activated: Boolean = false,
) {
    companion object {
        fun register(
            firstname: String,
            lastname: String,
        ): Customer = Customer(id = UUID.randomUUID().toString(), firstname = firstname, lastname = lastname)
    }

    fun activate() {
        activated = true
    }

    fun deactivate() {
        activated = false
    }
}
