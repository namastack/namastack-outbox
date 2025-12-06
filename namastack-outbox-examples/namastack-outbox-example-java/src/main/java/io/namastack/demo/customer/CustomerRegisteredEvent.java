package io.namastack.demo.customer;

import java.util.UUID;

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

  public UUID getId() {
    return id;
  }

  public String getFirstname() {
    return firstname;
  }

  public String getLastname() {
    return lastname;
  }

  public String getEmail() {
    return email;
  }
}
