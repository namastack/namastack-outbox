package io.namastack.demo.customer;

import io.namastack.outbox.annotation.OutboxEvent;
import java.util.UUID;

/**
 * Event fired when a customer registers.
 *
 * <p>The {@code name} attribute decouples the persisted {@code record_type} column from the
 * Java class FQCN. Rows written as {@code "customer.registered"} continue to dispatch even
 * if this class is later moved or renamed.
 *
 * <p>The {@code aliases} list allows rows written by an older version of the application
 * (which may have stored the full FQCN) to still resolve correctly.
 */
@OutboxEvent(
    name = "customer.registered",
    aliases = {"io.namastack.demo.customer.CustomerRegisteredEvent"}
)
public class CustomerRegisteredEvent {

    private final UUID id;
    private final String firstname;
    private final String lastname;
    private final String email;

    public CustomerRegisteredEvent(UUID id, String firstname, String lastname, String email) {
        this.id = id;
        this.firstname = firstname;
        this.lastname = lastname;
        this.email = email;
    }

    public UUID getId() { return id; }
    public String getFirstname() { return firstname; }
    public String getLastname() { return lastname; }
    public String getEmail() { return email; }
}
