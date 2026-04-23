package io.namastack.demo.customer

data class CustomerRegisteredEvent(
    val id: String,
    val firstname: String,
    val lastname: String,
    val email: String,
)
