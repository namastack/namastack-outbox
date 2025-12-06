package io.namastack.demo;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalBroker {

  private static final Logger logger = LoggerFactory.getLogger(ExternalBroker.class);

  private ExternalBroker() {
  }

  public static void publish(Object event, String key) {
    try {
      // Sleep between 50 and 150 milliseconds
      long sleepTime = ThreadLocalRandom.current().nextLong(50, 151);
      Thread.sleep(sleepTime);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Thread was interrupted", e);
    }

    // 40% chance of failure
    if (Math.random() < 0.4) {
      logger.warn("[External] Broker publish failed for key {}", key);
      throw new RuntimeException("Simulated failure in ExternalBroker");
    }

    logger.info("[External] Event published with key {}", key);
  }
}
