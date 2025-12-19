package io.namastack.demo;

import io.namastack.demo.customer.CustomerService;
import io.namastack.outbox.annotation.EnableOutbox;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableOutbox
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
  public void run(@NotNull String... args) throws Exception {
    logger.info("=== Namastack Outbox Demo ===");

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
