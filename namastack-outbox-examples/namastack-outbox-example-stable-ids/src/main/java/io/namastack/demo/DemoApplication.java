package io.namastack.demo;

import io.namastack.demo.customer.CustomerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(DemoApplication.class);

    private final CustomerService service;

    public DemoApplication(CustomerService service) {
        this.service = service;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("=== Stable IDs Demo ===");

        var customer1 = service.register("John", "Wayne", "john.wayne@example.com");
        var customer2 = service.register("Macy", "Grey", "macy.grey@example.com");

        Thread.sleep(2000);

        service.remove(customer1.getId());
        service.remove(customer2.getId());

        logger.info("=== Demo Complete ===");
    }
}
