package io.namastack.demo.customer;

import io.namastack.outbox.annotation.OutboxEvent;
import java.util.UUID;

/** Event fired when a customer is removed. Uses a stable logical {@code record_type}. */
@OutboxEvent(name = "customer.removed")
public class CustomerRemovedEvent {

    private final UUID id;

    public CustomerRemovedEvent(UUID id) {
        this.id = id;
    }

    public UUID getId() { return id; }
}
