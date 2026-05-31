package io.namastack.demo.customer;

import io.namastack.outbox.Outbox;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CustomerService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final Outbox outbox;

    public CustomerService(CustomerRepository customerRepository, Outbox outbox) {
        this.customerRepository = customerRepository;
        this.outbox = outbox;
    }

    @Transactional
    public Customer register(String firstname, String lastname, String email) {
        logger.debug("[Service] Register: {} {}", firstname, lastname);
        Customer customer = Customer.register(firstname, lastname, email);
        customerRepository.save(customer);
        outbox.schedule(
            new CustomerRegisteredEvent(customer.getId(), firstname, lastname, email),
            customer.getId().toString()
        );
        return customer;
    }

    @Transactional
    public void remove(UUID customerId) {
        logger.debug("[Service] Remove: {}", customerId);
        customerRepository.deleteById(customerId);
        outbox.schedule(new CustomerRemovedEvent(customerId), customerId.toString());
    }
}
