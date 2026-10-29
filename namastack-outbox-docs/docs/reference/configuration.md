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
      drain-on-full-batch: false                # Skip the delay after a full, productive batch (default: false)
      record-fetch-batch-size: 0                 # Keys loaded together; 0 keeps per-key reads
      max-records-per-key: 0                     # Ordered prefix per key per cycle; 0 is unlimited
      fixed:
        interval: 2s                           # interval between polling cycles (default: 2s)
      adaptive:
        min-interval: 1s                       # Minimum ms between cycles (default: 1s)
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

`processing-groups.enabled` controls new writes. Existing grouped records are decoded even when
it is `false`. Upgrade all workers before enabling it, and drain affected deliveries before changing
a handler's effective group. See [Processing Groups](processing-groups.md) for declarations, key
limits, and the rollout procedure.

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

## Draining full polling batches

Set `namastack.outbox.polling.drain-on-full-batch: true` to schedule the next cycle immediately
when the previous cycle loaded `batch-size` record keys, completed at least one record, and
encountered no failures or records waiting for retry. This works with both fixed and adaptive polling.
Partial or unproductive batches, failures, and compatibility cooldowns retain the polling delay.
The default is `false`.

Each cycle waits for all submitted key tasks, releases the assignment lock, and returns to the
scheduler before another cycle begins. A fair assignment lock lets a queued rebalance run before
the next batch acquires its assignments. Records for each key still run sequentially; this setting
does not change retry ordering or partition ownership. A slow handler can still delay rebalancing
until its current batch finishes.

## Bulk record reads and PostgreSQL completion batching

For an existing PostgreSQL schema, replace the old key index before enabling high-volume bulk
reads. Run each statement separately, outside a transaction, and adapt the names for your schema
or table prefix:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_key_status_created
    ON outbox_record (record_key, status, created_at);
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_record_incomplete_key_order
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_created;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_status;
DROP INDEX CONCURRENTLY IF EXISTS idx_outbox_record_record_key_completed_created;
```

Verify that both new indexes are valid before dropping any old indexes; `IF NOT EXISTS` does not
repair a previously failed concurrent index build. Keep the existing `(status, next_retry_at)`
and `(partition_no, status, next_retry_at)` indexes. The resulting record table has five indexes
including its primary key. New PostgreSQL schema
installations include the new index. It retains `record_key` as the leading column and adds
`status` to match delivery lookups.

For JDBC workloads, `namastack.outbox.polling.record-fetch-batch-size` can load several selected
keys with one query. The default `0` keeps per-key loading. Values from `1` through `1000` enable
bulk loading. Payloads are decoded by each key's worker only when that record is reached. Records
still follow `(created_at, id)` order, and a future retry blocks its successors when
`stop-on-first-failure` is enabled. Partition assignments remain held while fetched work finishes.
A decoding failure is isolated to its key.

`max-records-per-key` optionally bounds the ordered prefix processed in one cycle. It requires bulk
loading and defaults to unlimited. This can bound work before rebalancing for a hot key, but its
window query can cost more than an unlimited read; measure it with your key distribution.

PostgreSQL JDBC users can also opt into completion batching:

```yaml
namastack:
  outbox:
    jdbc:
      completion-batching:
        enabled: true
        max-batch-size: 20
        max-wait: 250us
```

Independent keys share a metadata update and database commit. Each worker waits for the commit
before processing its next record, and rebalancing waits for those acknowledgements. The batcher
flushes a partial batch after `max-wait`; a full batch can flush earlier. It does not wait for a
whole key's records or buffer all their completions. Maximum batch size is `512`.

The option defaults to disabled and supports PostgreSQL only. An existing application transaction
uses the normal completion path and its original transaction boundary. A failed shared transaction
never acknowledges its writes; a record-specific integrity error is isolated with individual
transactions after the batch rolls back. Handlers still need the library's usual idempotency for
at-least-once delivery.

Programmatic runtimes can create a writer with `JdbcOutboxPersistenceFactory.completionBatcher(...)`
and pass it as `completionBatcher` to `JdbcOutboxPersistenceFactory.create(...)`. Stop all runtimes
using that writer before closing it. The factory borrows the writer and does not close it or the
datasource. Use one writer per datasource and table namespace.
