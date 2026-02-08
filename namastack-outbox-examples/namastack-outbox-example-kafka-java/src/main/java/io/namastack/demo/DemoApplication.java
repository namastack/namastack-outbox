package io.namastack.demo;

import io.namastack.demo.customer.CustomerService;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
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
  public void run(String @NonNull ... args) throws Exception {
    logger.info("=== Namastack Outbox Kafka Demo (Java) ===");

    logger.info("Register: John Wayne");
    var customer1 = service.register("John", "Wayne", "john.wayne@example.com");

    logger.info("Register: Macy Grey");
    var customer2 = service.register("Macy", "Grey", "macy.grey@example.com");

    logger.info("Waiting for processing...");
    Thread.sleep(2000);

    logger.info("Remove: {}", customer1.getId());
    service.remove(customer1.getId());

    logger.info("Remove: {}", customer2.getId());
    service.remove(customer2.getId());

    logger.info("=== Demo Complete ===");
  }
}

