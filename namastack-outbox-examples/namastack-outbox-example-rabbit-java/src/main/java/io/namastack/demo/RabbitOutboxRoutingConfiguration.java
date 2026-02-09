package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.outbox.rabbit.RabbitOutboxRouting;
import io.namastack.outbox.routing.selector.OutboxPayloadSelector;
import java.util.Map;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Rabbit outbox routing using the Java builder API.
 *
 * <p>This demonstrates how to configure routing rules in pure Java:
 * <ul>
 *   <li>Route {@link CustomerRegisteredEvent} to a dedicated exchange with custom headers</li>
 *   <li>Use defaults for all other events</li>
 * </ul>
 */
@Configuration
public class RabbitOutboxRoutingConfiguration {

  @Bean
  public Exchange customerRegistrationsExchange() {
    return ExchangeBuilder
        .topicExchange("customer-registrations")
        .durable(true)
        .build();
  }

  @Bean
  public Exchange defaultExchange() {
    return ExchangeBuilder
        .topicExchange("default-exchange")
        .durable(true)
        .build();
  }

  @Bean
  public RabbitOutboxRouting rabbitOutboxRouting() {
    return RabbitOutboxRouting.builder()
        // Route CustomerRegisteredEvent to a dedicated exchange
        .route(OutboxPayloadSelector.type(CustomerRegisteredEvent.class), route -> {
          route.target("customer-registrations");
          route.key((payload, metadata) -> metadata.getKey());
          route.headers((payload, metadata) -> Map.of(
              "CustomerMail", ((CustomerRegisteredEvent) payload).getEmail()
          ));
        })
        // Default route for all other events
        .defaults(route -> {
          route.target("default-exchange");
          route.key((payload, metadata) -> metadata.getKey());
          route.headers((payload, metadata) -> Map.of(
              "eventType", payload.getClass().getSimpleName()
          ));
        })
        .build();
  }
}
