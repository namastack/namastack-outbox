package io.namastack.demo.customer

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
) {
    fun registerNew(
        firstname: String,
        lastname: String,
        email: String,
    ): Customer {
        val customer = Customer.register(firstname = firstname, lastname = lastname, email = email)
        return customerRepository.save(customer)
    }

    fun deactivate(id: UUID) {
        val customer = customerRepository.findById(id).orElseThrow()
        customer.deactivate()

        customerRepository.save(customer)
    }
}
