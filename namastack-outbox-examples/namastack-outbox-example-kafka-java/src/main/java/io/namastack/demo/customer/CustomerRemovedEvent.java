package io.namastack.demo.customer;

import java.util.UUID;

public class CustomerRemovedEvent {

    private final UUID id;

    public CustomerRemovedEvent(UUID id) {
        this.id = id;
    }

    public UUID getId() {
        return id;
    }
}

