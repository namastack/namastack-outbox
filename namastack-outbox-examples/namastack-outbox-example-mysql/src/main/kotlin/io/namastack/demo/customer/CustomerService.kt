package io.namastack.demo.customer

import com.fasterxml.jackson.databind.ObjectMapper
import io.namastack.outbox.OutboxRecord
import io.namastack.outbox.OutboxRecordRepository
import jakarta.transaction.Transactional
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class CustomerService(
    private val customerRepository: CustomerRepository,
    private val outboxRecordRepository: OutboxRecordRepository,
    private val clock: Clock,
) {
    @Transactional
    fun registerNew(
        firstname: String,
        lastname: String,
        email: String,
    ): Customer {
        val customer = Customer.register(firstname = firstname, lastname = lastname, email = email)
        val customerRegisteredEvent =
            CustomerRegisteredEvent(
                id = customer.id,
                firstname = customer.firstname,
                lastname = customer.lastname,
                email = customer.email,
            )

        outboxRecordRepository.save(
            record =
                OutboxRecord
                    .Builder()
                    .aggregateId(aggregateId = customer.id.toString())
                    .eventType(eventType = CustomerRegisteredEvent::class.simpleName!!)
                    .payload(payload = ObjectMapper().writeValueAsString(customerRegisteredEvent))
                    .build(clock),
        )

        return customerRepository.save(customer)
    }
}
