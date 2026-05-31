package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.demo.customer.CustomerRemovedEvent;
import io.namastack.outbox.annotation.OutboxHandler;
import io.namastack.outbox.handler.OutboxRecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Annotated method handler demonstrating stable {@code handler_id} via
 * {@code @OutboxHandler(id = "...")}.
 *
 * <p>Each method gets its own logical id. Rows written as
 * {@code "handlers.customer.registered"} or {@code "handlers.customer.removed"}
 * continue to dispatch even if this class is renamed or moved.
 */
@Component
public class CustomerEventHandler {

    private static final Logger logger = LoggerFactory.getLogger(CustomerEventHandler.class);

    @OutboxHandler(id = "handlers.customer.registered")
    public void onRegistered(CustomerRegisteredEvent event, OutboxRecordMetadata metadata) {
        logger.debug("[CustomerEventHandler] Publishing CustomerRegisteredEvent for key: {}", metadata.getKey());
        ExternalBroker.publish(event, metadata.getKey());
    }

    @OutboxHandler(id = "handlers.customer.removed")
    public void onRemoved(CustomerRemovedEvent event, OutboxRecordMetadata metadata) {
        logger.debug("[CustomerEventHandler] Publishing CustomerRemovedEvent for key: {}", metadata.getKey());
        ExternalBroker.publish(event, metadata.getKey());
    }
}
