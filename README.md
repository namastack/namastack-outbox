[![CodeFactor](https://www.codefactor.io/repository/github/namastack/namastack-outbox/badge)](https://www.codefactor.io/repository/github/namastack/namastack-outbox)
[![codecov](https://codecov.io/github/namastack/namastack-outbox/graph/badge.svg?token=TZS1OQB4XC)](https://codecov.io/github/namastack/namastack-outbox)
[![javadoc](https://javadoc.io/badge2/io.namastack/namastack-outbox-core/javadoc.svg)](https://javadoc.io/doc/io.namastack/namastack-outbox-core)
[![namastack-outbox CI](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml/badge.svg)](https://github.com/namastack/namastack-outbox/actions/workflows/gradle-test.yml)
[![GitHub Release Date](https://img.shields.io/github/release-date/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/releases/latest)
[![GitHub last commit](https://img.shields.io/github/last-commit/namastack/namastack-outbox)](https://github.com/namastack/namastack-outbox/commits/main)
[![dependabot](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)](https://img.shields.io/badge/dependabot-enabled-025E8C?logo=dependabot&logoColor=white)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# Namastack Outbox for Spring Boot

A robust Spring Boot library that implements the **Outbox Pattern** for reliable message publishing
in distributed systems — built and maintained by [Namastack](https://outbox.namastack.io).
This library ensures that domain events are published reliably, even in the face of system failures,
by using transactional guarantees and hash-based partitioning.

## Features

- 🔄 **Transactional Outbox Pattern**: Ensures events are never lost
- 🎯 **Hash-based Partitioning**: Automatic partition assignment for horizontal scaling
- 🔁 **Automatic Retry**: Multiple retry policies with configurable strategies
- 📊 **Event Ordering**: Guarantees event processing order per aggregate
- ⚡ **High Performance**: Optimized for high-throughput scenarios
- 🛡️ **Race Condition Safe**: Partition-based coordination prevents conflicts
- 📈 **Horizontally Scalable**: Dynamic instance coordination and rebalancing
- 🎯 **Zero Message Loss**: Database-backed reliability
- 🎲 **Jitter Support**: Randomized delays to prevent thundering herd
- 📊 **Built-in Metrics**: Comprehensive monitoring with Micrometer integration

## Quick Start

### 1. Add Dependencies

Add the library to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.namastack:namastack-outbox-starter-jpa:0.2.0")
}
```

Or if you're using Maven, add to your `pom.xml`:

```xml

<dependency>
  <groupId>io.namastack</groupId>
  <artifactId>namastack-outbox-starter-jpa</artifactId>
  <version>0.2.0</version>
</dependency>
```

### 2. Enable Outbox

Add the `@EnableOutbox` and `@EnableScheduling` annotations to your Spring Boot application:

```kotlin
@SpringBootApplication
@EnableOutbox
@EnableScheduling  // Required for automatic event processing
class YourApplication

fun main(args: Array<String>) {
    runApplication<YourApplication>(*args)
}
```

### 3. Configure Clock Bean

The library requires a Clock bean for time-based operations. Configure your own Clock, if you 
don't want to use the default Clock bean from namastack-outbox:

```kotlin
@Configuration
class OutboxConfiguration {

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
```

### 4. Configure Database

The library requires two database tables. You can enable automatic schema creation:

```yaml
outbox:
  schema-initialization:
    enabled: true
```

Or create the tables manually:

```sql
CREATE TABLE IF NOT EXISTS outbox_record
(
    id            VARCHAR(255)             NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    aggregate_id  VARCHAR(255)             NOT NULL,
    event_type    VARCHAR(255)             NOT NULL,
    payload       TEXT                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at  TIMESTAMP WITH TIME ZONE,
    retry_count   INT                      NOT NULL,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    partition     INTEGER                  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS outbox_instance
(
    instance_id    VARCHAR(255) PRIMARY KEY,
    hostname       VARCHAR(255)             NOT NULL,
    port           INTEGER                  NOT NULL,
    status         VARCHAR(50)              NOT NULL,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_record_aggregate_created
    ON outbox_record (aggregate_id, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_partition_status_retry
    ON outbox_record (partition, status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status_retry
    ON outbox_record (status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status
    ON outbox_record (status);

CREATE INDEX IF NOT EXISTS idx_outbox_record_aggregate_completed_created
    ON outbox_record (aggregate_id, completed_at, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status_heartbeat
    ON outbox_instance (status, last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_last_heartbeat
    ON outbox_instance (last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status
    ON outbox_instance (status);
```

### 5. Create Event Processor

Implement `OutboxRecordProcessor` to handle your events:

```kotlin
@Component
class MyEventProcessor : OutboxRecordProcessor {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()

    override fun process(record: OutboxRecord) {
        when (record.eventType) {
            "OrderCreated" -> handleOrderCreated(record)
            "OrderUpdated" -> handleOrderUpdated(record)
            "OrderCanceled" -> handleOrderCanceled(record)
            else -> logger.warn("Unknown event type: ${record.eventType}")
        }
    }

    private fun handleOrderCreated(record: OutboxRecord) {
        val event = objectMapper.readValue(record.payload, OrderCreatedEvent::class.java)
        // Publish to message broker, call external API, etc.
        messagePublisher.publish("orders.created", event)
    }
}
```

### 6. Save Events to Outbox

Inject `OutboxRecordRepository`, `Clock`, and save events using the OutboxRecord Builder:

```kotlin
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRecordRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock  // Inject Clock for consistent timestamps
) {

    @Transactional
    fun createOrder(command: CreateOrderCommand): Order {
        // Create and save the order
        val order = Order.create(command)
        orderRepository.save(order)

        // Save event to outbox - same transaction!
        val event = OrderCreatedEvent(order.id, order.customerId, order.amount)
        val outboxRecord = OutboxRecord.Builder()
            .aggregateId(order.id.toString())
            .eventType("OrderCreated")
            .payload(objectMapper.writeValueAsString(event))
            .build(clock)  // Pass clock for consistent timestamps

        outboxRepository.save(outboxRecord)

        return order
    }

    @Transactional
    fun updateOrder(orderId: OrderId, command: UpdateOrderCommand): Order {
        val order = orderRepository.findById(orderId)
            ?: throw OrderNotFoundException(orderId)

        order.update(command)
        orderRepository.save(order)

        // Create update event using Builder
        val event = OrderUpdatedEvent(order.id, order.customerId, order.amount)
        val outboxRecord = OutboxRecord.Builder()
            .aggregateId(order.id.toString())
            .eventType("OrderUpdated")
            .payload(objectMapper.writeValueAsString(event))
            .build(clock)

        outboxRepository.save(outboxRecord)

        return order
    }
}
```

**Alternative: Using OutboxRecord.restore() for specific field values**

For testing or when you need to specify all properties (like retry count, status, etc.), use the
`restore` method:

```kotlin
// For testing or when recreating records with specific states
val outboxRecord = OutboxRecord.restore(
    id = UUID.randomUUID().toString(),
    aggregateId = order.id.toString(),
    eventType = "OrderCreated",
    payload = objectMapper.writeValueAsString(event),
    partition = 1,
    createdAt = OffsetDateTime.now(clock),
    status = OutboxRecordStatus.NEW,
    completedAt = null,
    retryCount = 0,
    nextRetryAt = OffsetDateTime.now(clock)
)
```

**When to use which method:**

- **Builder**: Use for creating new events in your business logic (recommended for most cases)
- **restore()**: Use in tests or when you need to recreate records with specific field values

## Configuration

Configure the outbox behavior in your `application.yml`:

```yaml
outbox:
  # Polling interval for processing events (milliseconds)
  poll-interval: 2000
  
  # Batch size for processing events
  batch-size: 10

  # Schema initialization
  schema-initialization:
    enabled: true

  # Processing behavior configuration
  processing:
    stop-on-first-failure: true  # Stop processing aggregate when one event fails (default: true)

  # Instance coordination and partition management
  instance:
    graceful-shutdown-timeout-seconds: 15     # Timeout for graceful shutdown
    stale-instance-timeout-seconds: 30        # When to consider an instance stale
    heartbeat-interval-seconds: 5             # Heartbeat frequency
    new-instance-detection-interval-seconds: 10  # Instance discovery frequency

  # Retry configuration
  retry:
    max-retries: 3             # Maximum retry attempts (applies to all policies)
    policy: "exponential"      # Main retry policy: fixed, exponential, or jittered

    # Exponential backoff configuration
    exponential:
      initial-delay: 2000      # Start with 2 seconds
      max-delay: 60000         # Cap at 60 seconds  
      multiplier: 2.0          # Double each time

    # Fixed delay configuration
    fixed:
      delay: 5000              # Always wait 5 seconds

    # Jittered retry configuration (adds randomness to base policy)
    jittered:
      base-policy: exponential # Base policy: fixed or exponential
      jitter: 500              # Add 0-500ms random jitter
```

## Hash-based Partitioning

The library uses hash-based partitioning to enable horizontal scaling across multiple application 
instances while maintaining strict event ordering per aggregate (if activated).

### How Partitioning Works

1. **Consistent Hashing**: Each outbox record is assigned to a partition based on its `aggregateId` using MurmurHash3
2. **Fixed Partition Count**: The system uses 256 fixed partitions (configurable at compile time)
3. **Dynamic Assignment**: Partitions are automatically distributed among active instances
4. **Automatic Rebalancing**: When instances join or leave, partitions are reassigned

### Partition Assignment

```kotlin
// Each aggregate always maps to the same partition
val partition = PartitionHasher.getPartitionForAggregate("order-123")
// partition will always be the same value for "order-123"
```

### Instance Coordination

The library automatically coordinates multiple instances:

```yaml
outbox:
  instance:
    graceful-shutdown-timeout-seconds: 15     # Time to wait for graceful shutdown
    stale-instance-timeout-seconds: 30        # When to consider an instance dead
    heartbeat-interval-seconds: 5             # How often instances send heartbeats
    new-instance-detection-interval-seconds: 10  # How often to check for new instances
```

### Scaling Behavior

- **Scale Up**: New instances automatically receive partition assignments
- **Scale Down**: Partitions from stopped instances are redistributed to remaining instances
- **Load Balancing**: Partitions are distributed as evenly as possible across instances

### Example: 3 Instances with 256 Partitions

```
Instance 1: Partitions 0-84   (85 partitions)
Instance 2: Partitions 85-169 (85 partitions) 
Instance 3: Partitions 170-255 (86 partitions)
```

When Instance 2 goes down:
```
Instance 1: Partitions 0-84, 170-211   (127 partitions)
Instance 3: Partitions 85-169, 212-255 (129 partitions)
```

### Ordering Guarantees

✅ **Per-Aggregate Ordering**: All events for the same aggregate are processed in order  
✅ **Cross-Instance Safety**: Only one instance processes events for each aggregate  
✅ **Failure Recovery**: Partitions are automatically reassigned when instances fail  
✅ **No Manual Configuration**: Partition assignment is fully automatic

The library provides configurable processing behavior to handle different use cases and
requirements.

### Stop on First Failure

Control how the scheduler handles failures within an aggregate:

```yaml
outbox:
  processing:
    stop-on-first-failure: true  # Default behavior
```

**`stop-on-first-failure: true` (Default)**

- When one event fails, processing stops for the remaining events in that aggregate
- Maintains strict event ordering within aggregates
- Prevents potential cascading issues from dependent events
- Recommended when events within an aggregate have dependencies

**`stop-on-first-failure: false`**

- When one event fails, processing continues with the next events in the same aggregate
- Maximizes throughput by allowing independent events to proceed
- Failed events will be retried according to the retry policy
- Recommended when events within an aggregate are independent

**Example Use Cases:**

```yaml
# E-commerce scenario - events depend on each other
outbox:
  processing:
    stop-on-first-failure: true  # If OrderCreated fails, don't process OrderShipped
```

```yaml
# Logging/Analytics scenario - events are independent  
outbox:
  processing:
    stop-on-first-failure: false  # Continue logging other events even if one fails
```

**Behavior Impact:**

| Configuration    | Event 1   | Event 2 | Event 3    | Result                         |
|------------------|-----------|---------|------------|--------------------------------|
| `true` (default) | ✅ Success | ❌ Fails | ⏸️ Skipped | Event 2 retried, Event 3 waits |
| `false`          | ✅ Success | ❌ Fails | ✅ Success  | Event 2 retried independently  |

## Retry Mechanisms

The library provides sophisticated retry mechanisms to handle transient failures gracefully.
Multiple retry policies are available to suit different use cases.

### Retry Policies

#### 1. Fixed Delay Retry Policy

Retries with a constant delay between attempts:

```yaml
outbox:
  retry:
    policy: "fixed"
    max-retries: 5
    fixed:
      delay: 5000  # Always wait 5 seconds between retries
```

**Use case**: Simple scenarios where you want consistent retry intervals.

#### 2. Exponential Backoff Retry Policy

Implements exponential backoff with configurable initial and maximum delays:

```yaml
outbox:
  retry:
    policy: "exponential"
    max-retries: 10
    exponential:
      initial-delay: 1000    # Start with 1 second
      max-delay: 300000      # Cap at 5 minutes
      multiplier: 2.0        # Backoff multiplier
```

**Retry schedule**:

- Retry 1: 1 second
- Retry 2: 2 seconds
- Retry 3: 4 seconds
- Retry 4: 8 seconds
- Retry 5: 16 seconds
- ...continues doubling until `max-delay`

**Use case**: Most common scenario - gradually back off to reduce load on failing systems.

#### 3. Jittered Retry Policy

Adds randomization to any base policy to prevent thundering herd problems:

```yaml
outbox:
  retry:
    policy: "jittered"
    max-retries: 7
    jittered:
      base-policy: exponential # Base policy: fixed or exponential
      jitter: 1000             # Add 0-1000ms random jitter
    exponential: # Configure the base policy
      initial-delay: 2000
      max-delay: 60000
      multiplier: 2.0
```

**Example with exponential base**:

- Base delay: 2 seconds → Actual delay: 2.0-3.0 seconds
- Base delay: 4 seconds → Actual delay: 4.0-5.0 seconds
- Base delay: 8 seconds → Actual delay: 8.0-9.0 seconds

**Use case**: High-traffic systems where many instances might retry simultaneously.

### Custom Retry Policies

You can implement custom retry logic by creating a bean that implements `OutboxRetryPolicy`:

```kotlin
@Component
class CustomRetryPolicy : OutboxRetryPolicy {

    override fun shouldRetry(exception: Throwable): Boolean {
        // Only retry on specific exceptions
        return when (exception) {
            is HttpRetryException,
            is SocketTimeoutException,
            is ConnectException -> true
            is SecurityException -> false  // Never retry auth failures
            else -> true
        }
    }

    override fun nextDelay(retryCount: Int): Duration {
        // Custom delay logic
        return when {
            retryCount <= 2 -> Duration.ofSeconds(1)      // Quick retries first
            retryCount <= 5 -> Duration.ofSeconds(30)     // Medium delays
            else -> Duration.ofMinutes(5)                 // Longer delays for persistent failures
        }
    }
}
```

### Retry Behavior Configuration

#### Exception-Based Retry Logic

Control which exceptions should trigger retries:

```kotlin
@Component
class SelectiveRetryPolicy : OutboxRetryPolicy {

    override fun shouldRetry(exception: Throwable): Boolean {
        return when (exception) {
            // Retry transient failures
            is SocketTimeoutException,
            is HttpRetryException,
            is ConnectTimeoutException -> true

            // Don't retry business logic failures
            is ValidationException,
            is AuthenticationException,
            is IllegalArgumentException -> false

            // Default: retry unknown exceptions
            else -> true
        }
    }

    // ...existing code...
}
```

### Error Handling

The library automatically handles retries with the configured policy. Here's what happens when
processing fails:

1. **Exception Occurs**: During event processing
2. **Retry Decision**: `shouldRetry(exception)` determines if retry should happen
3. **Retry Count Check**: Verifies retry count hasn't exceeded `max-retries`
4. **Delay Calculation**: `nextDelay(retryCount)` calculates wait time
5. **Scheduling**: Event is scheduled for retry at calculated time
6. **Final Failure**: After max retries, event is marked as `FAILED`

## Metrics

The `namastack-outbox-metrics` module provides comprehensive metrics for Outbox records and 
partition distribution, integrating automatically with Micrometer and Spring Boot Actuator.

### Prerequisites

- The JPA module (`namastack-outbox-jpa`) must be included.
- Micrometer and Spring Boot Actuator must be present and configured as dependencies.
- The `@EnableOutbox` annotation must be set in your application.

### Integration

Add the metrics module to your dependencies:

```kotlin
dependencies {
    implementation("io.namastack:namastack-outbox-metrics:0.2.0")
}
```

Make sure the Actuator endpoints are enabled (e.g. in `application.properties`):

```properties
management.endpoints.web.exposure.include=health, info, metrics
```

### Available Metrics

#### Record Status Metrics

The module registers gauges for each Outbox status:

- `outbox.records.count{status="new|failed|completed"}` - Count of records by status

#### Partition Metrics

The module also provides partition-level metrics for monitoring load distribution:

- `outbox.partitions.assigned.count` - Number of partitions assigned to this instance
- `outbox.partitions.pending.records.total` - Total pending records across assigned partitions
- `outbox.partitions.pending.records.max` - Maximum pending records in any assigned partition
- `outbox.partitions.pending.records.avg` - Average pending records per assigned partition

#### Cluster Metrics

Monitor cluster-wide partition distribution:

- `outbox.cluster.instances.total` - Total number of active instances in the cluster
- `outbox.cluster.partitions.total` - Total number of partitions (always 256)
- `outbox.cluster.partitions.avg_per_instance` - Average partitions per instance

### Example: Querying Metrics

```shell
# Record status metrics
curl http://localhost:8080/actuator/metrics/outbox.records.count

# Partition metrics
curl http://localhost:8080/actuator/metrics/outbox.partitions.assigned.count
curl http://localhost:8080/actuator/metrics/outbox.cluster.instances.total
```

### Prometheus Integration

If Prometheus is enabled in Spring Boot Actuator (e.g. by adding
`implementation("io.micrometer:micrometer-registry-prometheus")` and enabling the endpoint), all
Outbox metrics are available under `/actuator/prometheus`:

```
# Record metrics
outbox_records_count{status="new",...} <value>
outbox_records_count{status="failed",...} <value>
outbox_records_count{status="completed",...} <value>

# Partition metrics
outbox_partitions_assigned_count{...} <value>
outbox_partitions_pending_records_total{...} <value>
outbox_partitions_pending_records_max{...} <value>
outbox_partitions_pending_records_avg{...} <value>

# Cluster metrics
outbox_cluster_instances_total{...} <value>
outbox_cluster_partitions_total{...} <value>
outbox_cluster_partitions_avg_per_instance{...} <value>
```

### Grafana Dashboard

Use these metrics to create monitoring dashboards:

- **Load Distribution**: Monitor `outbox.partitions.pending.records.*` across instances
- **Cluster Health**: Track `outbox.cluster.instances.total` for instance failures
- **Processing Backlog**: Watch `outbox.records.count{status="new"}` for backlogs
- **Failure Rate**: Monitor `outbox.records.count{status="failed"}` for issues

## Monitoring

Monitor outbox status and partition distribution:

```kotlin
@Service
class OutboxMonitoringService(
    private val outboxRepository: OutboxRecordRepository,
    private val partitionMetricsProvider: OutboxPartitionMetricsProvider
) {

    fun getPendingEvents(): List<OutboxRecord> {
        return outboxRepository.findPendingRecords()
    }

    fun getFailedEvents(): List<OutboxRecord> {
        return outboxRepository.findFailedRecords()
    }

    fun getCompletedEvents(): List<OutboxRecord> {
        return outboxRepository.findCompletedRecords()
    }

    fun getPartitionStats(): PartitionProcessingStats {
        return partitionMetricsProvider.getProcessingStats()
    }

    fun getClusterStats(): PartitionStats {
        return partitionMetricsProvider.getPartitionStats()
    }
}
```

## How It Works

### Outbox Pattern

1. **Transactional Write**: Events are saved to the outbox table in the same transaction as your domain changes
2. **Hash-based Partitioning**: Each event is assigned to a partition based on its aggregateId
3. **Instance Coordination**: Partitions are automatically distributed among active instances
4. **Background Processing**: A scheduler polls for unprocessed events in assigned partitions
5. **Ordered Processing**: Events are processed in creation order per aggregate
6. **Retry Logic**: Failed events are automatically retried with configurable policies

### Hash-based Partitioning

- **Consistent Hashing**: Each aggregate maps to the same partition using MurmurHash3
- **Fixed Partitions**: 256 partitions provide fine-grained load distribution
- **Dynamic Assignment**: Partitions are automatically redistributed when instances join/leave
- **Load Balancing**: Even distribution of partitions across all active instances

### Instance Coordination

- **Heartbeat System**: Instances send regular heartbeats to indicate they're alive
- **Automatic Discovery**: New instances are automatically detected and included
- **Failure Detection**: Stale instances are detected and their partitions redistributed
- **Graceful Shutdown**: Instances can shutdown gracefully, releasing their partitions

### Reliability Guarantees

✅ **At-least-once delivery**: Events will be processed at least once  
✅ **Ordering per aggregate**: Events for the same aggregate are processed in order  
✅ **Failure recovery**: System failures don't result in lost events  
✅ **Horizontal scalability**: Multiple instances process different partitions concurrently  
✅ **Automatic rebalancing**: Partitions are redistributed when instances change

## Testing

The library is thoroughly tested with:

- **Unit Tests**: All components with high coverage
- **Integration Tests**: Real database and partitioning scenarios
- **Concurrency Tests**: Race condition validation
- **Performance Tests**: High-throughput scenarios

Run tests:

```bash
./gradlew test
```

## Migration from 0.1.0 to 0.2.0

Version 0.2.0 introduces significant architectural improvements, transitioning from distributed locking to **hash-based partitioning** for better horizontal scaling and performance. This change requires database schema updates.

### Key Changes in 0.2.0

🎯 **Hash-based Partitioning**: Replaced distributed locking with partition-based coordination  
📊 **Instance Management**: New `outbox_instance` table for coordinating multiple instances  
🔢 **Partition Field**: Added `partition` column to `outbox_record` table  
📈 **Enhanced Performance**: Optimized queries and improved throughput  
📊 **Built-in Metrics**: Comprehensive monitoring with partition-level visibility

### Database Schema Changes

The 0.2.0 release introduces:

1. **New `outbox_instance` table** for instance coordination
2. **New `partition` column** in `outbox_record` table
3. **Additional database indexes** for optimal performance
4. **Removal of lock-related tables** (if you used the distributed locking approach)

### Migration Steps

#### Option 1: Simple Migration (Recommended)

The **easiest and safest approach** is to drop existing outbox tables and let the library recreate them with the new schema:

```sql
-- Stop all application instances first
-- This ensures no events are being processed during migration

-- Drop existing tables (this will lose existing outbox data)
DROP TABLE IF EXISTS outbox_record;
DROP TABLE IF EXISTS outbox_lock;  -- If you have this from 0.1.0

-- Update your application to version 0.2.0
-- The new schema will be automatically created on startup if schema-initialization is enabled
```

**When to use this approach:**
- ✅ You can afford to lose unprocessed outbox events
- ✅ You're okay with a brief service interruption
- ✅ You want the simplest migration path
- ✅ You're in development or staging environment

#### Option 2: Data Preservation Migration

If you need to preserve existing outbox data, please **contact the maintainer** for assistance with a custom migration script. This requires:

- Migrating existing records to the new partition-based structure
- Calculating partition assignments for existing records
- Handling any failed or pending events appropriately

**When you need custom migration support:**
- 🔄 You have critical unprocessed events that must be preserved
- 🏭 You're migrating in a production environment with strict data requirements
- 📊 You need to maintain event processing history

### Verification Steps

After migration, verify the setup:

1. **Check Tables**: Ensure `outbox_record` and `outbox_instance` tables exist
2. **Verify Partitioning**: Confirm that new records have `partition` values assigned
3. **Test Scaling**: Start multiple instances and verify partition assignment works
4. **Monitor Metrics**: Use the new metrics endpoints to monitor partition distribution

### Breaking Changes

- **Removed**: Distributed lock-based coordination
- **Changed**: `OutboxRecord` now includes partition information
- **New**: Instance coordination requires heartbeat mechanism
- **New**: Automatic partition assignment for horizontal scaling

### Need Help?

If you cannot use the simple drop-and-recreate approach and need to preserve existing outbox data, please **contact the maintainer** by opening a GitHub issue.

## Supported Databases

Namastack Outbox supports the following relational databases:

- **H2** (for development and testing)
- **MariaDB**
- **MySQL**
- **Oracle**
- **PostgreSQL**
- **SQL Server**

All supported databases are tested with the default schema and index definitions provided by the library. If you encounter compatibility issues or require support for another database, please open a GitHub issue.

### Database Compatibility Notes

- **H2**: Recommended for development and CI testing only.
- **MariaDB/MySQL**: Fully supported. Use InnoDB for transactional guarantees.
- **Oracle**: Supported with standard schema. Ensure correct data types for timestamps and text fields.
- **PostgreSQL**: Fully supported and recommended for production.
- **SQL Server**: Supported. Make sure to use the correct dialect in your JPA configuration.

## Requirements

- **Java**: 21+
- **Spring Boot**: 3.0+
- **Database**: H2, MariaDB, MySQL, Oracle, PostgreSQL, SQL Server
- **Kotlin**: 2.2+

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

## Support

For questions and issues, please open a GitHub issue.

## License

This project is licensed under
the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0.html).

## Trademarks

Spring®, Spring Boot®, and the Spring leaf logo are trademarks of Broadcom Inc. and/or its
subsidiaries in the United States and other countries.

Java™ and OpenJDK™ are trademarks or registered trademarks of Oracle and/or its affiliates.

PostgreSQL®, MySQL®, and other database names used herein are trademarks of their respective owners.

“AWS” and “Amazon Web Services” are trademarks or registered trademarks of Amazon.com, Inc. or its
affiliates.

Apache®, Apache Kafka®, Apache Tomcat®, and Apache Cassandra™ are trademarks or registered
trademarks of the Apache Software Foundation in the United States and/or other countries.

All other trademarks and copyrights are property of their respective owners and are used only for
identification or descriptive purposes.

This project, Namastack Outbox for Spring, is an independent open-source project and is not
affiliated with, endorsed by, or sponsored by Broadcom Inc. or the Spring team.
