package io.namastack.demo;

import io.namastack.outbox.EnableOutbox;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@EnableOutbox
@EnableScheduling
@SpringBootApplication
public class DemoApplication implements CommandLineRunner {

  private final Logger logger = LoggerFactory.getLogger(DemoApplication.class);
  private final ApplicationEventPublisher eventPublisher;

  public DemoApplication(ApplicationEventPublisher eventPublisher) {
    this.eventPublisher = eventPublisher;
  }

  static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }

  @Override
  @Transactional
  public void run(String... args) {
    logger.info("Starting Namastack Outbox Demo Application");

    eventPublisher.publishEvent(
        new CustomerRegisteredEvent(UUID.randomUUID().toString(), "John", "Jones",
            "john.jones@test.de"));
  }

  @Component
  static
  class TestListener {

    @EventListener
    public void onEvent(CustomerRegisteredEvent event) {
      System.out.println(event);
    }
  }
}
