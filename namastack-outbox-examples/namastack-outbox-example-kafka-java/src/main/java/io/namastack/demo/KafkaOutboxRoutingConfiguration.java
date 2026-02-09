package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.outbox.kafka.KafkaOutboxRouting;
import io.namastack.outbox.routing.selector.OutboxPayloadSelector;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Kafka outbox routing using the Java builder API.
 * <p>
 * This demonstrates how to configure routing rules in pure Java: - Route CustomerRegisteredEvent to
 * a specific topic with custom headers - Use defaults for all other events
 */
@Configuration
public class KafkaOutboxRoutingConfiguration {

  @Bean
  public KafkaOutboxRouting kafkaOutboxRouting() {
    return KafkaOutboxRouting.builder()
        // Route CustomerRegisteredEvent to customer-registrations topic
        .route(OutboxPayloadSelector.type(CustomerRegisteredEvent.class), route -> {
          route.target("customer-registrations");
          route.key((payload, metadata) -> metadata.getKey());
          route.headers((payload, metadata) -> Map.of(
              "CustomerMail", ((CustomerRegisteredEvent) payload).getEmail()
          ));
        })
        // Default route for all other events
        .defaults(route -> {
          route.target("default-topic");
          route.key((payload, metadata) -> metadata.getKey());
          route.headers((payload, metadata) -> Map.of(
              "eventType", payload.getClass().getSimpleName()
          ));
        })
        .build();
  }
}

