package io.namastack.demo;

import io.namastack.demo.customer.CustomerRegisteredEvent;
import io.namastack.outbox.handler.OutboxHandlerIdentity;
import io.namastack.outbox.handler.OutboxRecordMetadata;
import io.namastack.outbox.handler.OutboxTypedHandler;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CustomerRegisteredOutboxHandler implements OutboxTypedHandler<CustomerRegisteredEvent> {

  private static final Logger logger = LoggerFactory.getLogger(CustomerRegisteredOutboxHandler.class);

  @Override
  public OutboxHandlerIdentity getTypedHandlerIdentity() {
    return new OutboxHandlerIdentity("customers.send-registration-email");
  }

  @Override
  public void handle(CustomerRegisteredEvent payload, @NonNull OutboxRecordMetadata metadata) {
    logger.info("[Handler] Send email to: {}", payload.getEmail());
    ExternalMailService.send(payload.getEmail());
  }
}
