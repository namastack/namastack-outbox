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
      trigger: fixed                          # Polling strategy: fixed|adaptive (default: fixed)
      batch-size: 10                          # Record keys per poll cycle (default: 10)
      fixed:
        interval: 2000                        # Milliseconds between polling cycles (default: 2000)
      adaptive:
        min-interval: 1000                    # Minimum ms between cycles (default: 1000)
        max-interval: 8000                    # Maximum ms between cycles (default: 8000)
    # Legacy (deprecated)
    poll-interval: 2000                       # (deprecated) Use polling.fixed.interval
    rebalance-interval: 10000                 # (deprecated) Use instance.rebalance-interval
    batch-size: 10                            # (deprecated) Use polling.batch-size

    # Processing Configuration
    processing:
      stop-on-first-failure: true              # Stop processing on first failure (default: true)
      delete-completed-records: false          # Delete records after completion (default: false)
      executor-core-pool-size: 4               # Core threads for processing (default: 4, platform threads)
      executor-max-pool-size: 8                # Maximum threads for processing (default: 8, platform threads)
      executor-concurrency-limit: -1           # Concurrency limit for virtual threads (default: -1 unlimited)

    # Event Multicaster Configuration
    multicaster:
      enabled: true                            # Enable @OutboxEvent interception (default: true)
      publish-after-save: true                 # Publish events to listeners after saving (default: true)

    # Instance Coordination Configuration
    instance:
      graceful-shutdown-timeout-seconds: 0     # Graceful shutdown propagation window (default: 0)
      stale-instance-timeout-seconds: 30       # When an instance is considered stale and removed (default: 30)
      heartbeat-interval-seconds: 5            # How often each instance sends a heartbeat (default: 5)
      rebalance-interval: 10000                # How often partitions are recalculated (default: 10000)

    jdbc:
      table-prefix: ""                         # Prefix for table names (default: empty)
      schema-name: null                        # Database schema name (default: null, uses default schema)
      schema-initialization:
        enabled: true                          # Auto-create tables on startup (default: true)

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
        delay: 5000                            # Delay in milliseconds (default: 5000)
      
      # Linear Backoff Policy
      linear:
        initial-delay: 2000                    # Initial delay in milliseconds (default: 2000)
        increment: 2000                        # Increment per retry in milliseconds (default: 2000)
        max-delay: 60000                       # Maximum delay cap in milliseconds (default: 60000)
      
      # Exponential Backoff Policy
      exponential:
        initial-delay: 1000                    # Initial delay in milliseconds (default: 1000)
        max-delay: 60000                       # Maximum delay cap in milliseconds (default: 60000)
        multiplier: 2.0                        # Backoff multiplier (default: 2.0)
      
      # Jitter Configuration (can be used with any policy)
      jitter: 0                                # Max random jitter in milliseconds (default: 0)

    # Kafka Integration
    kafka:
      enabled: true                           # Enable Kafka outbox integration (default: true)
      default-topic: outbox-events            # Default Kafka topic (default: outbox-events)
      enable-json: true                       # Enable JSON support (default: true)

    # RabbitMQ Integration
    rabbit:
      enabled: true                           # Enable Rabbit outbox integration (default: true)
      default-exchange: outbox-events         # Default Rabbit exchange (default: outbox-events)
      enable-json: true                       # Enable JSON support (default: true)
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

