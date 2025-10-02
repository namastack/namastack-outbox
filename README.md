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

Or if you're using Maven, add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.beisel</groupId>
    <artifactId>spring-outbox-jpa</artifactId>
    <version>0.0.1-SNAPSHOT</version>
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

The library requires a Clock bean for time-based operations. Add this configuration:

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
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS outbox_lock
(
    aggregate_id VARCHAR(255)             NOT NULL,
    acquired_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    PRIMARY KEY (aggregate_id)
);

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id_created_at ON outbox_record (aggregate_id, created_at);

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

For testing or when you need to specify all properties (like retry count, status, etc.), use the `restore` method:

```kotlin
// For testing or when recreating records with specific states
val outboxRecord = OutboxRecord.restore(
    id = UUID.randomUUID().toString(),
    aggregateId = order.id.toString(),
    eventType = "OrderCreated",
    payload = objectMapper.writeValueAsString(event),
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
  # Polling interval for processing events
  poll-interval: 5s

  # Schema initialization
  schema-initialization:
    enabled: true

  # Distributed locking settings  
  locking:
    extension-seconds: 300     # Lock duration (5 minutes)
    refresh-threshold: 60      # Renew lock when < 60s remaining

  # Processing behavior configuration
  processing:
    stop-on-first-failure: true  # Stop processing aggregate when one event fails (default: true)

  # Retry configuration
  retry:
    max-retries: 3             # Maximum retry attempts (applies to all policies)
    policy: "exponential"      # Main retry policy: fixed, exponential, or jittered
    
    # Exponential backoff configuration
    exponential:
      initial-delay: 1000      # Start with 1 second
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

## Processing Behavior

The library provides configurable processing behavior to handle different use cases and requirements.

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
    exponential:               # Configure the base policy
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

Das Modul `spring-outbox-metrics` stellt Metriken für Outbox-Records bereit und integriert sich automatisch mit Micrometer und Spring Boot Actuator.

### Voraussetzungen
- Das JPA-Modul (`spring-outbox-jpa`) muss eingebunden sein.
- Micrometer und Spring Boot Actuator müssen als Abhängigkeit vorhanden und konfiguriert sein.
- Die Annotation `@EnableOutbox` muss in deiner Anwendung gesetzt sein.

### Integration
Füge das Metrics-Modul zu deinen Abhängigkeiten hinzu:

```kotlin
dependencies {
    implementation("com.beisel:spring-outbox-metrics:0.0.1-SNAPSHOT")
}
```

Stelle sicher, dass die Actuator-Endpunkte aktiviert sind (z.B. in `application.properties`):
```properties
management.endpoints.web.exposure.include=*
```

### Verfügbare Metriken
Das Modul registriert für jeden Outbox-Status (NEW, FAILED, COMPLETED) einen Gauge mit dem Namen:

- `outbox.records.count{status="new|failed|completed"}`

Diese Metriken zeigen die Anzahl der Outbox-Records pro Status an und können z.B. über `/actuator/metrics/outbox.records.count` abgefragt werden.

### Beispiel: Metrik-Abfrage

```shell
curl http://localhost:8080/actuator/metrics/outbox.records.count
```

Das Ergebnis enthält die Werte für alle Status als separate Zeitreihen.

### Prometheus Integration

Wenn Prometheus in Spring Boot Actuator aktiviert ist (z.B. durch Hinzufügen von `implementation("io.micrometer:micrometer-registry-prometheus")` und Aktivierung des Endpunkts), sind die Outbox-Metriken auch unter `/actuator/prometheus` verfügbar. Sie erscheinen dort z.B. als:

```
outbox_records_count{status="new",...} <Wert>
outbox_records_count{status="failed",...} <Wert>
outbox_records_count{status="completed",...} <Wert>
```

Dadurch können die Metriken direkt von Prometheus abgefragt und für Dashboards oder Alerts verwendet werden.

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
