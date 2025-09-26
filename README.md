[![CodeFactor](https://www.codefactor.io/repository/github/rolandbeisel/spring-outbox/badge)](https://www.codefactor.io/repository/github/rolandbeisel/spring-outbox)

# Spring Outbox

A robust Spring Boot library that implements the **Outbox Pattern** for reliable message publishing
in distributed systems. This library ensures that domain events are published reliably, even in the
face of system failures, by using transactional guarantees and distributed locking.

## Features

- 🔄 **Transactional Outbox Pattern**: Ensures events are never lost
- 🔒 **Distributed Locking**: Prevents concurrent processing of the same aggregate
- 🔁 **Automatic Retry**: Multiple retry policies with configurable strategies
- 📊 **Event Ordering**: Guarantees event processing order per aggregate
- ⚡ **High Performance**: Optimized for high-throughput scenarios
- 🛡️ **Race Condition Safe**: Uses optimistic locking to handle concurrency
- 📈 **Scalable**: Supports multiple application instances
- 🎯 **Zero Message Loss**: Database-backed reliability
- 🎲 **Jitter Support**: Randomized delays to prevent thundering herd

## Quick Start

### 1. Add Dependencies

Add the library to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.beisel:spring-outbox-jpa:0.0.1-SNAPSHOT")
}
```

### 2. Enable Outbox

Add the `@EnableOutbox` annotation to your Spring Boot application:

```kotlin
@SpringBootApplication
@EnableOutbox
class YourApplication

fun main(args: Array<String>) {
    runApplication<YourApplication>(*args)
}
```

### 3. Configure Database

The library requires two database tables. You can enable automatic schema creation:

```yaml
outbox:
  schema-initialization:
    enabled: true
```

Or create the tables manually:

```sql
CREATE TABLE outbox_record
(
    id            VARCHAR(255) PRIMARY KEY,
    status        VARCHAR(20)              NOT NULL,
    aggregate_id  VARCHAR(255)             NOT NULL,
    event_type    VARCHAR(255)             NOT NULL,
    payload       TEXT                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at  TIMESTAMP WITH TIME ZONE,
    retry_count   INT       DEFAULT 0,
    next_retry_at TIMESTAMP DEFAULT now()  NOT NULL
);

CREATE TABLE outbox_lock
(
    aggregate_id VARCHAR(255) PRIMARY KEY,
    acquired_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT
);

CREATE INDEX idx_outbox_aggregate_id_created_at ON outbox_record (aggregate_id, created_at);
```

### 4. Create Event Processor

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

### 5. Save Events to Outbox

Inject `OutboxRecordRepository` and save events in your domain services:

```kotlin
@Service
@Transactional
class OrderService(
    private val orderRepository: OrderRepository,
    private val outboxRepository: OutboxRecordRepository,
    private val objectMapper: ObjectMapper
) {

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
            .build()

        outboxRepository.save(outboxRecord)

        return order
    }
}
```

## Configuration

Configure the outbox behavior in your `application.yml`:

```yaml
outbox:
  # Polling interval for processing events
  poll-interval: 5s

  # Schema initialization
  schema-initialization:
    enabled: true

  # Distributed locking settings  
  locking:
    extension-seconds: 300     # Lock duration (5 minutes)
    refresh-threshold: 60      # Renew lock when < 60s remaining

  # Retry configuration
  retry:
    max-retries: 3             # Maximum retry attempts
    policy: "exponential"      # Retry policy: fixed, exponential, or jittered
    initial-delay: 1000        # Initial delay in milliseconds
    max-delay: 60000           # Maximum delay in milliseconds
    jitter: 500                # Jitter amount in milliseconds (for jittered policy)
```

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
    initial-delay: 5000  # Always wait 5 seconds between retries
    max-retries: 5
```

**Use case**: Simple scenarios where you want consistent retry intervals.

#### 2. Exponential Backoff Retry Policy

Implements exponential backoff with configurable initial and maximum delays:

```yaml
outbox:
  retry:
    policy: "exponential"
    initial-delay: 1000    # Start with 1 second
    max-delay: 300000      # Cap at 5 minutes
    max-retries: 10
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
    initial-delay: 2000
    max-delay: 60000
    jitter: 1000          # Add 0-1000ms random jitter
    max-retries: 7
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

## Monitoring

Query outbox status:

```kotlin
@Service
class OutboxMonitoringService(
    private val outboxRepository: OutboxRecordRepository
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
}
```

## How It Works

### Outbox Pattern

1. **Transactional Write**: Events are saved to the outbox table in the same transaction as your
   domain changes
2. **Background Processing**: A scheduler polls for unprocessed events
3. **Distributed Locking**: Only one instance processes events for each aggregate
4. **Ordered Processing**: Events are processed in creation order per aggregate
5. **Retry Logic**: Failed events are automatically retried with exponential backoff

### Distributed Locking

- Each aggregate gets its own lock to prevent concurrent processing
- Locks automatically expire and can be overtaken by other instances
- Optimistic locking prevents race conditions during lock renewal
- Lock-free processing for different aggregates enables horizontal scaling

### Reliability Guarantees

✅ **At-least-once delivery**: Events will be processed at least once  
✅ **Ordering per aggregate**: Events for the same aggregate are processed in order  
✅ **Failure recovery**: System failures don't result in lost events  
✅ **Scalability**: Multiple instances can process different aggregates concurrently

## Testing

The library is thoroughly tested with:

- **Unit Tests**: All components with high coverage
- **Integration Tests**: Real database and locking scenarios
- **Concurrency Tests**: Race condition validation
- **Performance Tests**: High-throughput scenarios

Run tests:

```bash
./gradlew test
```

## Requirements

- **Java**: 17+
- **Spring Boot**: 3.0+
- **Database**: PostgreSQL, MySQL, H2, or any JPA-supported database
- **Kotlin**: 1.9+

## License

This project is licensed under the MIT License.

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes with tests
4. Submit a pull request

## Support

For questions and issues, please open a GitHub issue.
