package io.namastack.demo.customer

import java.util.UUID

data class Customer(
    val id: String,
    val firstname: String,
    val lastname: String,
    val email: String,
) {
    companion object {
        fun register(
            firstname: String,
            lastname: String,
            email: String,
        ): Customer =
            Customer(
                id = UUID.randomUUID().toString(),
                firstname = firstname,
                lastname = lastname,
                email = email,
            )
    }
}
