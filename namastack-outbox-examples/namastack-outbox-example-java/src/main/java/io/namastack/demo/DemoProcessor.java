package io.namastack.demo;

import io.namastack.outbox.OutboxRecord;
import io.namastack.outbox.OutboxRecordProcessor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DemoProcessor implements OutboxRecordProcessor {

  private final Logger logger = LoggerFactory.getLogger(DemoProcessor.class);

  @Override
  public void process(@NotNull OutboxRecord record) {
    logger.info("Processing {} for aggregate {}", record.getEventType(), record.getAggregateId());
    simulateExternalServiceCall();
  }

  private void simulateExternalServiceCall() {
    try {
      // Simulate network delay
      long delay = 50 + (long) (Math.random() * 101); // 50–150 ms
      Thread.sleep(delay);

      // Occasionally simulate failures for retry demonstration
      if (Math.random() < 0.3) { // 30% failure rate
        logger.warn("❌ Service temporarily unavailable - will retry");
        throw new RuntimeException("Simulated failure in DemoProcessor");
      }

      logger.info("✅ Processing completed");

    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Thread interrupted", e);
    }
  }
}
