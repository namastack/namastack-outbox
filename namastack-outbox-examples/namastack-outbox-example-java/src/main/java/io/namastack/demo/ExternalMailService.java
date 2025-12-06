package io.namastack.demo;

import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExternalMailService {

  private static final Logger logger = LoggerFactory.getLogger(ExternalMailService.class);

  private ExternalMailService() {
  }

  public static void send(String email) {
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
      logger.warn("[External] Mail service failed for {}", email);
      throw new RuntimeException("Simulated failure in ExternalMailService");
    }

    logger.info("[External] Mail sent to {}", email);
  }
}
