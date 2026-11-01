---
custom_edit_url: null
pagination_prev: null
pagination_next: null
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
        min-interval: 1s                       # Minimum interval between cycles, at least 1ms (default: 1s)
        max-interval: 8s                       # Maximum ms between cycles (default: 8s)
    # Legacy (deprecated)
    poll-interval: 2s                          # (deprecated) Use polling.fixed.interval
    rebalance-interval: 10s                    # (deprecated) Use instance.rebalance-interval
    batch-size: 10                             # (deprecated) Use polling.batch-size

    # Processing Groups (since 1.12.0)
    processing-groups:
      enabled: false                          # Use declared groups for new deliveries (default: false)

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
      schema-name: null                        # Database schema name (default: null, uses default schema)
      table-prefix: ""                         # Prefix for table names (default: empty)
      table-names:
        record: "outbox_record"                # Base name for the records table (default: outbox_record)
        instance: "outbox_instance"            # Base name for the instances table (default: outbox_instance)
        partition: "outbox_partition"          # Base name for the partitions table (default: outbox_partition)
      schema-initialization:
        enabled: true                          # Auto-create tables on startup (default: true)
      completion-batching:
        enabled: true                          # Batch committed JDBC completions (default: true)
        max-batch-size: 2048                    # Completions per transaction (default: 2048)
        max-wait: 1ms                           # Collection window, excluding database time (default: 1ms)
        queue-capacity: 4096                    # Waiting completions per instance (default: 4096)
        writer-count: 1                         # Concurrent completion transactions (default: 1)
        bulk-updates: false                     # Opt-in PostgreSQL multi-row UPDATE (default: false)

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
      idempotency-header-enabled: false        # Add Idempotency-Key (default: false)
      default-topic: outbox-events             # Default Kafka topic (default: outbox-events)
      enable-json: true                        # Enable JSON support (default: true)

    # RabbitMQ Integration
    rabbit:
      enabled: true                            # Enable Rabbit outbox integration (default: true)
      idempotency-header-enabled: false        # Add Idempotency-Key (default: false)
      default-exchange: outbox-events          # Default Rabbit exchange (default: outbox-events)
      enable-json: true                        # Enable JSON support (default: true)
      publisher-confirm-timeout: 10s           # Maximum time to wait for publisher confirms (default: 10s)
      fail-on-unroutable: false                # Fail when RabbitMQ returns an unroutable message (default: false)

    # SNS Integration
    sns:
      enabled: true                            # Enable SNS outbox integration (default: true)
      idempotency-header-enabled: false        # Add Idempotency-Key attribute (default: false)
      fifo-deduplication-id-enabled: false     # Set native FIFO deduplication ID (default: false)
      default-topic-arn: arn:aws:sns:us-east-1:000000000000:outbox-events   # Default SNS topic ARN (default: arn:aws:sns:us-east-1:000000000000:outbox-events)
```

:::info RabbitMQ publisher confirms
Rabbit outbox publishing requires Spring AMQP correlated publisher confirms:

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated
```

If `namastack.outbox.rabbit.fail-on-unroutable=true`, also enable publisher returns and mandatory
publishing:

```yaml
spring:
  rabbitmq:
    publisher-returns: true
    template:
      mandatory: true
```

See [RabbitMQ Integration](messaging.md#rabbitmq-integration) for the full reliability semantics.
:::

---

## JDBC Completion Batching

Completion batching combines successful processing-state updates from independent record keys in one
transaction. Each worker waits for the transaction to commit before advancing to the next record of
its key. The payload and context are not serialized or rewritten. Failed processing-state updates
and writes inside an application transaction remain synchronous; application rollback still owns
those writes. `delete-completed-records=true` uses deletion and does not benefit from this update batcher.

`max-wait` starts when a writer receives the first completion. It bounds collection time, not queue
waiting, database execution, or commit duration. A full batch flushes immediately. Each writer uses
one database connection; size the pool for processing, polling, heartbeats, and application traffic
too. Queue overflow fails the completion write and prevents advancing that key; it does not discard
an accepted completion or report success. At-least-once processing means the handler can run again.
Shutdown drains accepted requests before closing the writer.

Batching is enabled by default for a fixed datasource. Routing datasources, including Spring
`DelegatingDataSource` proxies around them, retain synchronous writes so the caller's tenant context
is preserved. Disable batching explicitly for custom routing wrappers or connection initialization
that depends on thread-local context. Custom `outboxJdbcClient`, `outboxJdbcOperations`, and
`outboxTransactionTemplate` beans must address the same datasource and transaction manager.

`bulk-updates=true` selects PostgreSQL's multi-row update implementation. Other JDBC databases retain
portable statement batching. This is opt-in because PostgreSQL statement-level triggers run once per
multi-row statement instead of once per record. Row-level triggers still run for each updated row.
Use ordinary batching when statement-level trigger behavior must remain unchanged.

Drivers that do not return exact JDBC batch update counts fall back to individual statements in a
shared transaction after the first inexact batch has rolled back. Commit failures propagate without
replaying a transaction whose outcome may be unknown.

See [Performance Tuning](performance-tuning.md#jdbc-processing-optimizations) for normal application
settings, and [Database Support](database.md#postgresql-processing-indexes) for PostgreSQL migrations.

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
