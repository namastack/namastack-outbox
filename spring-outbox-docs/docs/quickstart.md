---
hide:
  - navigation
---

# Quick Start

**Spring Outbox** is a minimal-configuration Spring Boot library for reliably publishing domain 
events using the Outbox Pattern.

It works out of the box: you just add the dependency, enable the outbox, and provide 
a `OutboxRecordProcessor` bean. The library handles storing, processing, and retrying events automatically,
so you can focus on your business logic instead of wiring infrastructure.

This guide will get you up and running in minutes, showing the simplest setup for transactional 
event publishing with minimal boilerplate.

---

## 🧩 1. Add Dependency

Add the library to your project.

=== "Gradle"

    ```kotlin
    dependencies {
        implementation("io.namastack:spring-outbox-jpa:0.1.0")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.namastack</groupId>
      <artifactId>spring-outbox-starter-jpa</artifactId>
      <version>0.1.0</version>
    </dependency>
    ```

## ⚙️ 2. Enable Spring Outbox

Annotate your application class to enable outbox processing:

=== "Kotlin"

    ```kotlin
    @SpringBootApplication
    @EnableOutbox
    @EnableScheduling  // required for automatic processing
    class YourApplication
    
    fun main(args: Array<String>) {
        runApplication<YourApplication>(*args)
    }
    ```

=== "Java"

    ```java
    @SpringBootApplication
    @EnableOutbox
    @EnableScheduling  // required for automatic processing
    public class YourApplication {
    
        public static void main(String[] args) {
            SpringApplication.run(YourApplication.class, args);
        }
    }
    ```

## 🕒 3. Provide a Clock Bean

Spring Outbox uses a Clock for reliable, testable timestamps.

=== "Kotlin"

    ```kotlin
    @Configuration
    class OutboxConfiguration {

        @Bean
        fun clock(): Clock = Clock.systemUTC()
    }
    ```

=== "Java"

    ```java
    @Configuration
    public class OutboxConfiguration {
    
        @Bean
        public Clock clock() {
            return Clock.systemUTC();
        }
    }
    ```

## 🗄️ 4. Configure the Database

Let the library create its schema automatically:

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

## 🧠 5. Implement Your Processor

You decide how events are published — to Kafka, RabbitMQ, SNS, or any other broker.

=== "Kotlin"

    ```kotlin
    @Component
    class MyEventProcessor : OutboxRecordProcessor {
    
        private val logger = LoggerFactory.getLogger(javaClass)
        private val objectMapper = ObjectMapper()
    
        override fun process(record: OutboxRecord) {
            when (record.eventType) {
                "OrderCreated" -> handleOrderCreated(record)
                else -> logger.warn("Unknown event type: ${record.eventType}")
            }
        }
    
        private fun handleOrderCreated(record: OutboxRecord) {
            val event = objectMapper.readValue(record.payload, OrderCreatedEvent::class.java)
            messagePublisher.publish("orders.created", event)
        }
    }
    ```

=== "Java"

    ```java
    @Component
    public class MyEventProcessor implements OutboxRecordProcessor {
    
        private static final Logger logger = LoggerFactory.getLogger(MyEventProcessor.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
    
        // Assume you have some MessagePublisher bean injected
        private final MessagePublisher messagePublisher;
    
        public MyEventProcessor(MessagePublisher messagePublisher) {
            this.messagePublisher = messagePublisher;
        }
    
        @Override
        public void process(OutboxRecord record) {
            switch (record.getEventType()) {
                case "OrderCreated":
                    handleOrderCreated(record);
                    break;
                default:
                    logger.warn("Unknown event type: {}", record.getEventType());
                    break;
            }
        }
    
        private void handleOrderCreated(OutboxRecord record) {
            try {
                OrderCreatedEvent event = objectMapper.readValue(record.getPayload(), OrderCreatedEvent.class);
                messagePublisher.publish("orders.created", event);
            } catch (Exception e) {
                logger.error("Failed to process OrderCreated event", e);
            }
        }
    }
    ```

## 🧾 6. Write Events Transactionally

Store events in the outbox within the same transaction as your entity:

=== "Kotlin"

    ```kotlin
    @Service
    class OrderService(
        private val orderRepository: OrderRepository,
        private val outboxRepository: OutboxRecordRepository,
        private val objectMapper: ObjectMapper,
        private val clock: Clock
    ) {
    
        @Transactional
        fun createOrder(command: CreateOrderCommand): Order {
            val order = Order.create(command)
            orderRepository.save(order)
    
            val event = OrderCreatedEvent(order.id, order.customerId, order.amount)
            val record = OutboxRecord.Builder()
                .aggregateId(order.id.toString())
                .eventType("OrderCreated")
                .payload(objectMapper.writeValueAsString(event))
                .build(clock)
    
            outboxRepository.save(record)
            return order
        }
    }
    ```

=== "Java"

    ```java
    @Service
    public class OrderService {
    
        private final OrderRepository orderRepository;
        private final OutboxRecordRepository outboxRepository;
        private final ObjectMapper objectMapper;
        private final Clock clock;
    
        public OrderService(OrderRepository orderRepository,
                            OutboxRecordRepository outboxRepository,
                            ObjectMapper objectMapper,
                            Clock clock) {
            this.orderRepository = orderRepository;
            this.outboxRepository = outboxRepository;
            this.objectMapper = objectMapper;
            this.clock = clock;
        }
    
        @Transactional
        public Order createOrder(CreateOrderCommand command) {
            // Create and save the order
            Order order = Order.create(command);
            orderRepository.save(order);
    
            // Create the event
            OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getAmount());
    
            try {
                // Build the outbox record
                OutboxRecord record = new OutboxRecord.Builder()
                        .aggregateId(order.getId().toString())
                        .eventType("OrderCreated")
                        .payload(objectMapper.writeValueAsString(event))
                        .build(clock);
    
                // Save the outbox record
                outboxRepository.save(record);
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize OrderCreatedEvent", e);
            }
    
            return order;
        }
    }
    ```

## ⚡ 7. Configuration Overview

Configure the outbox behavior in your application.yml:

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
