package io.namastack.demo.customer

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
) {
    private val logger = LoggerFactory.getLogger(CustomerService::class.java)

    fun register(
        firstName: String,
        lastName: String,
    ): Customer {
        logger.info("📝 Registering customer: $firstName $lastName")

        val customer = Customer.register(firstName, lastName)
        customer.addRegisteredEvent()

        return customerRepository.save(customer)
    }

    fun activate(customerId: String): Customer {
        logger.info("🎉 Activating customer: $customerId")

        val customer =
            customerRepository
                .findById(customerId)
                .orElseThrow { IllegalArgumentException("Customer not found: $customerId") }

        customer.activate()

        return customerRepository.save(customer)
    }

    fun deactivate(customerId: String): Customer {
        logger.info("😔 Deactivating customer: $customerId")

        val customer =
            customerRepository
                .findById(customerId)
                .orElseThrow { IllegalArgumentException("Customer not found: $customerId") }

        customer.deactivate()

        return customerRepository.save(customer)
    }
}
