package io.namastack.demo;

import io.namastack.outbox.handler.OutboxHandler;
import io.namastack.outbox.handler.OutboxRecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GenericOutboxHandler implements OutboxHandler {

  private static final Logger logger = LoggerFactory.getLogger(GenericOutboxHandler.class);

  @Override
  public void handle(Object payload, OutboxRecordMetadata metadata) {
    String className = payload.getClass().getSimpleName();
    logger.info("[Handler] Publish {}: {}", className, metadata.getKey());
    ExternalBroker.publish(payload, metadata.getKey());
  }
}
