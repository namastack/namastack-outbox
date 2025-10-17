package io.namastack.demo.customer

import com.fasterxml.jackson.databind.ObjectMapper
import io.namastack.springoutbox.OutboxRecord
import io.namastack.springoutbox.OutboxRecordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val outboxRecordRepository: OutboxRecordRepository,
) {
    @Transactional
    fun register(
        firstName: String,
        lastName: String,
    ): Customer {
        val customer = Customer.register(firstName, lastName)
        customerRepository.save(customer)

        val registeredEvent = CustomerRegisteredEvent(customer.id, customer.firstname, customer.lastname)

        outboxRecordRepository.save(
            OutboxRecord
                .Builder()
                .aggregateId(customer.id)
                .eventType(CustomerRegisteredEvent::class.simpleName.toString())
                .payload(ObjectMapper().writeValueAsString(registeredEvent))
                .build(),
        )

        return customer
    }

    @Transactional
    fun activate(customerId: String): Customer {
        val customer = customerRepository.findById(customerId).orElseThrow()

        customer.activate()

        val activatedEvent = CustomerActivatedEvent(customer.id)

        outboxRecordRepository.save(
            OutboxRecord
                .Builder()
                .aggregateId(customer.id)
                .eventType(CustomerActivatedEvent::class.simpleName.toString())
                .payload(ObjectMapper().writeValueAsString(activatedEvent))
                .build(),
        )

        return customer
    }

    @Transactional
    fun deactivate(customerId: String): Customer {
        val customer = customerRepository.findById(customerId).orElseThrow()

        customer.deactivate()

        val deactivatedEvent = CustomerDeactivatedEvent(customer.id)

        outboxRecordRepository.save(
            OutboxRecord
                .Builder()
                .aggregateId(customer.id)
                .eventType(CustomerDeactivatedEvent::class.simpleName.toString())
                .payload(ObjectMapper().writeValueAsString(deactivatedEvent))
                .build(),
        )

        return customer
    }
}
