package io.namastack.demo;

import io.namastack.outbox.OutboxEvent;

@OutboxEvent(key = "#root.id")
public record CustomerRegisteredEvent(String id, String firstname, String lastname, String email) {

}
