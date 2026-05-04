---
title: Configuration
description: Complete reference of all configuration options.
sidebar_position: 1
---

# Configuration Reference

Complete reference of all configuration options:

```yaml
namastack:
  outbox:
    enabled: true                              # Enable outbox functionality (default: true)

    # Polling Configuration
    polling:
      trigger: fixed                           # Polling strategy: fixed|adaptive (default: fixed)
      batch-size: 10                           # Record keys per poll cycle (default: 10)
      fixed:
        interval: 2s                           # interval between polling cycles (default: 2s)
      adaptive:
        min-interval: 1s                       # Minimum ms between cycles (default: 1s)
        max-interval: 8s                       # Maximum ms between cycles (default: 8s)
    # Legacy (deprecated)
    poll-interval: 2s                          # (deprecated) Use polling.fixed.interval
    rebalance-interval: 10s                    # (deprecated) Use instance.rebalance-interval
    batch-size: 10                             # (deprecated) Use polling.batch-size

    # Processing Configuration
    processing:
      stop-on-first-failure: true              # Stop processing on first failure (default: true)
      delete-completed-records: false          # Delete records after completion (default: false)
      executor-core-pool-size: 4               # Core threads for processing (default: 4, platform threads)
      executor-max-pool-size: 8                # Maximum threads for processing (default: 8, platform threads)
      executor-concurrency-limit: -1           # Concurrency limit for virtual threads (default: -1 unlimited)
      shutdown-timeout-seconds: 30             # (deprecated) Use shutdown-timeout
      shutdown-timeout: 30s                    # Maximum time to wait for processing to complete during shutdown (default: 30s)

    # Event Multicaster Configuration
    multicaster:
      enabled: true                            # Enable @OutboxEvent interception (default: true)
      publish-after-save: true                 # Publish events to listeners after saving (default: true)

    # Instance Coordination Configuration
    instance:
      graceful-shutdown-timeout-seconds: 0     # (deprecated) Use graceful-shutdown-timeout
      stale-instance-timeout-seconds: 30       # (deprecated) Use stale-instance-timeout
      heartbeat-interval-seconds: 5            # (deprecated) Use heartbeat-interval
      graceful-shutdown-timeout: 0s            # Graceful shutdown propagation window (default: 0s)
      stale-instance-timeout: 30s              # When an instance is considered stale and removed (default: 30s)
      heartbeat-interval: 5s                   # How often each instance sends a heartbeat (default: 5s)
      rebalance-interval: 10s                  # How often partitions are recalculated (default: 10s)

    jdbc:
      table-prefix: ""                         # Prefix for table names (default: empty)
      schema-name: null                        # Database schema name (default: null, uses default schema)
      schema-initialization:
        enabled: true                          # Auto-create tables on startup (default: true)

    mongodb:
      collection-prefix: ""                    # Prefix for collection names (default: empty)

    # Retry Configuration
    retry:
      policy: exponential                      # Retry policy: fixed|linear|exponential (default: exponential)
      max-retries: 3                           # Maximum retry attempts (default: 3)
      include-exceptions:                      # Only retry these exceptions (optional)
        - java.net.SocketTimeoutException
        - org.springframework.web.client.ResourceAccessException
      exclude-exceptions:                      # Never retry these exceptions (optional)
        - java.lang.IllegalArgumentException
        - javax.validation.ValidationException
      
      # Fixed Delay Policy
      fixed:
        delay: 5s                              # Delay (default: 5s)
      
      # Linear Backoff Policy
      linear:
        initial-delay: 2s                      # Initial delay  (default: 2s)
        increment: 2s                          # Increment per retry (default: 2s)
        max-delay: 1m                          # Maximum delay cap (default: 1m)
      
      # Exponential Backoff Policy
      exponential:
        initial-delay: 2s                      # Initial delay (default: 2s)
        max-delay: 1m                          # Maximum delay cap (default: 1m)
        multiplier: 2.0                        # Backoff multiplier (default: 2.0)
      
      # Jitter Configuration (can be used with any policy)
      jitter: 0s                               # Max random jitter (default: 0s)

    # Kafka Integration
    kafka:
      enabled: true                            # Enable Kafka outbox integration (default: true)
      default-topic: outbox-events             # Default Kafka topic (default: outbox-events)
      enable-json: true                        # Enable JSON support (default: true)

    # RabbitMQ Integration
    rabbit:
      enabled: true                            # Enable Rabbit outbox integration (default: true)
      default-exchange: outbox-events          # Default Rabbit exchange (default: outbox-events)
      enable-json: true                        # Enable JSON support (default: true)

    # SNS Integration
    sns:
      enabled: true                            # Enable SNS outbox integration (default: true)
      default-topic-arn: arn:aws:sns:us-east-1:000000000000:outbox-events   # Default SNS topic ARN (default: arn:aws:sns:us-east-1:000000000000:outbox-events)
```

---

## Disabling Outbox

To completely disable outbox functionality:

```yaml
namastack:
  outbox:
    enabled: false
```

This prevents all outbox beans from being created, useful for:

- Running tests without outbox processing
- Temporarily disabling outbox in specific environments
- Conditional feature flags

---

## Automatic Scheduling

Namastack Outbox automatically enables Spring's `@EnableScheduling` when included as a dependency. This is required for internal components like the polling scheduler, partition rebalancer, and instance heartbeat to function.

:::info No manual setup needed
You do **not** need to add `@EnableScheduling` to your application class — the library handles this automatically.
:::

**How it works:**

- If your application does **not** already have `@EnableScheduling`, the library enables it via auto-configuration.
- If your application **already** has `@EnableScheduling`, the library detects the existing `ScheduledAnnotationBeanPostProcessor` bean and skips its own activation — no duplicate registration occurs.
- If the outbox is disabled (`namastack.outbox.enabled=false`), scheduling is **not** activated by the library.

**Opting out:**

If you need to prevent the library from enabling scheduling (e.g., in a test slice), disable the outbox entirely:

```yaml
namastack:
  outbox:
    enabled: false
```
