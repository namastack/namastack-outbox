package io.namastack.demo.customer

import io.namastack.outbox.Outbox
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val outbox: Outbox,
) {
    private val logger = LoggerFactory.getLogger(CustomerService::class.java)

    @Transactional
    fun register(
        firstname: String,
        lastname: String,
        email: String,
    ): Customer {
        logger.info("[Service] Register: {} {}", firstname, lastname)
        val customer = Customer.register(firstname = firstname, lastname = lastname, email = email)
        val customerId = customer.id

        customerRepository.save(customer)
        logger.info("[Service] Saved to DB: {}", customerId)

        outbox.schedule(
            payload =
                CustomerRegisteredEvent(
                    id = customerId,
                    firstname = customer.firstname,
                    lastname = customer.lastname,
                    email = customer.email,
                ),
            key = customerId.toString(),
        )
        logger.info("[Service] Scheduled to Outbox: {}", customerId)

        return customer
    }

    @Transactional
    fun remove(customerId: UUID) {
        logger.info("[Service] Remove: {}", customerId)
        customerRepository.deleteById(customerId)

        outbox.schedule(payload = CustomerRemovedEvent(customerId), key = customerId.toString())
        logger.info("[Service] Scheduled to Outbox: {}", customerId)
    }
}
