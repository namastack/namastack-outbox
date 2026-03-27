package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.outbox.sns.SnsOutboxRouting;
import io.namastack.outbox.routing.selector.OutboxPayloadSelector;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for SNS outbox routing using the Java builder API.
 *
 * <p>This demonstrates how to configure routing rules in pure Java:
 * <ul>
 *   <li>Route {@code CustomerRegisteredEvent} to a specific SNS topic ARN with custom message attributes</li>
 *   <li>Use a default topic ARN for all other events</li>
 * </ul>
 */
@Configuration
public class SnsOutboxRoutingConfiguration {

    @Bean
    public SnsOutboxRouting snsOutboxRouting() {
        return SnsOutboxRouting.builder()
            // Route CustomerRegisteredEvent to the customer-registrations topic
            .route(OutboxPayloadSelector.type(CustomerRegisteredEvent.class), route -> {
                route.target("arn:aws:sns:us-east-1:000000000000:customer-registrations");
                route.key((payload, metadata) -> metadata.getKey());
                route.headers((payload, metadata) -> Map.of(
                    "CustomerMail", ((CustomerRegisteredEvent) payload).getEmail()
                ));
            })
            // Default route for all other events
            .defaults(route -> {
                route.target("arn:aws:sns:us-east-1:000000000000:default-topic");
                route.key((payload, metadata) -> metadata.getKey());
                route.headers((payload, metadata) -> Map.of(
                    "eventType", payload.getClass().getSimpleName()
                ));
            })
            .build();
    }
}

