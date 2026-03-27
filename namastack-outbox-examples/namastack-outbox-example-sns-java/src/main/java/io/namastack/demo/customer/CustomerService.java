package io.namastack.demo.customer;

import io.namastack.outbox.Outbox;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final Outbox outbox;
    private final Logger logger = LoggerFactory.getLogger(CustomerService.class);

    public CustomerService(CustomerRepository customerRepository, Outbox outbox) {
        this.customerRepository = customerRepository;
        this.outbox = outbox;
    }

    @Transactional
    public Customer register(String firstname, String lastname, String email) {
        logger.info("[Service] Register: {} {}", firstname, lastname);

        Customer customer = Customer.register(firstname, lastname, email);
        UUID customerId = customer.getId();

        customerRepository.save(customer);
        logger.info("[Service] Saved to DB: {}", customerId);

        CustomerRegisteredEvent event = new CustomerRegisteredEvent(
            customerId,
            customer.getFirstname(),
            customer.getLastname(),
            customer.getEmail()
        );

        outbox.schedule(event, customerId.toString());
        logger.info("[Service] Scheduled to Outbox: {}", customerId);

        return customer;
    }

    @Transactional
    public void remove(UUID customerId) {
        logger.info("[Service] Remove: {}", customerId);

        customerRepository.deleteById(customerId);

        CustomerRemovedEvent event = new CustomerRemovedEvent(customerId);
        outbox.schedule(event, customerId.toString());

        logger.info("[Service] Scheduled to Outbox: {}", customerId);
    }
}

