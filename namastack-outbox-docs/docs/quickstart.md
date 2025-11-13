# Quickstart

**Namastack Outbox for Spring Boot** is a minimal-configuration Spring Boot library for reliably 
publishing domain events using the Outbox Pattern.

It works out of the box: you just add the dependency, enable the outbox, and provide 
a `OutboxRecordProcessor` bean. The library handles storing, processing, and retrying events automatically,
so you can focus on your business logic instead of wiring infrastructure.

This guide will get you up and running in minutes, showing the simplest setup for transactional 
event publishing with minimal boilerplate.

---

## Add Dependency

Add the library to your project.

=== "Gradle"

    ```kotlin
    dependencies {
        implementation("io.namastack:namastack-outbox-starter-jpa:{{ outbox_version }}")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
      <groupId>io.namastack</groupId>
      <artifactId>namastack-outbox-starter-jpa</artifactId>
      <version>{{ outbox_version }}</version>
    </dependency>
    ```

## Enable Namastack Outbox for Spring Boot

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

## Provide a Clock Bean

Namastack Outbox for Spring Boot uses a Clock to ensure reliable and testable timestamps. If you do not provide a Clock, namastack-outbox will create one automatically using `systemDefaultZone`.

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

## Configure the Database

Let the library create its schema automatically:

```yaml
outbox:
  schema-initialization:
    enabled: true
```

Or create the tables manually. You can look up the latest database schemas for all supported databases [here](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jpa/src/main/resources/schema).

## Implement Your Processor

You decide how events are published — to Kafka, RabbitMQ, SNS, or any other broker.

=== "Kotlin"

    ```kotlin
    @Component
    class MyEventProcessor(val messagePublisher: MessagePublisher) : OutboxRecordProcessor {

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

## Write Events Transactionally

Namastack Outbox ensures that your domain events are reliably stored and published as part of the same transaction as your business data. There are two main ways to achieve this:

### 1. Using @DomainEvents and ApplicationEventPublisher (Recommended)

With Spring Data JPA, you can leverage the `@DomainEvents` annotation on your aggregate root (usually by extending `AbstractAggregateRoot`). This allows you to collect domain events during your business logic and have them automatically published by Spring after the entity is saved.

**How it works:**
- Annotate a method in your aggregate with `@DomainEvents` to return a list of events.
- After the entity is persisted, Spring automatically publishes these events using the `ApplicationEventPublisher`.
- Namastack Outbox intercepts these events (if annotated with `@OutboxEvent`) and stores them in the outbox table, ensuring transactional consistency.

=== "Kotlin"

    ```kotlin
    @Entity
    class Order(...) : AbstractAggregateRoot<Order>() {
        ...existing code...
        fun markCreated() {
            // business logic
            registerEvent(OrderCreatedEvent(id, ...))
        }
    }
    ```

    ```kotlin
    // In your service
    @Transactional
    fun createOrder(command: CreateOrderCommand): Order {
        val order = Order.create(command)
        order.markCreated()
        orderRepository.save(order) // triggers @DomainEvents
        return order
    }
    ```

=== "Java"

    ```java
    @Entity
    public class Order extends AbstractAggregateRoot<Order> {
        ...existing code...
        public void markCreated() {
            // business logic
            registerEvent(new OrderCreatedEvent(id, ...));
        }
    }
    ```

    ```java
    // In your service
    @Transactional
    public Order createOrder(CreateOrderCommand command) {
        Order order = Order.create(command);
        order.markCreated();
        orderRepository.save(order); // triggers @DomainEvents
        return order;
    }
    ```

### 2. Using ApplicationEventPublisher Directly

You can also publish events directly using Spring's `ApplicationEventPublisher`. If your event class is annotated with `@OutboxEvent`, Namastack Outbox will automatically intercept and persist it in the outbox table.

=== "Kotlin"

    ```kotlin
    @Service
    class OrderService(
        private val orderRepository: OrderRepository,
        private val applicationEventPublisher: ApplicationEventPublisher
    ) {
        @Transactional
        fun createOrder(command: CreateOrderCommand): Order {
            val order = Order.create(command)
            orderRepository.save(order)
            val event = OrderCreatedEvent(order.id, ...)
            applicationEventPublisher.publishEvent(event)
            return order
        }
    }
    ```

=== "Java"

    ```java
    @Service
    public class OrderService {
        private final OrderRepository orderRepository;
        private final ApplicationEventPublisher applicationEventPublisher;
        public OrderService(OrderRepository orderRepository, ApplicationEventPublisher applicationEventPublisher) {
            this.orderRepository = orderRepository;
            this.applicationEventPublisher = applicationEventPublisher;
        }
        @Transactional
        public Order createOrder(CreateOrderCommand command) {
            Order order = Order.create(command);
            orderRepository.save(order);
            OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), ...);
            applicationEventPublisher.publishEvent(event);
            return order;
        }
    }
    ```

### 3. Manual Approach: Using OutboxRecordRepository

If you need full control, you can always use the manual approach by injecting `OutboxRecordRepository` and saving events directly. This is useful for advanced scenarios or when you want to set all fields explicitly.

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

            val event = OrderCreatedEvent(order.id, ...)
            val outboxRecord = OutboxRecord.Builder()
                .aggregateId(order.id.toString())
                .eventType("OrderCreated")
                .payload(objectMapper.writeValueAsString(event))
                .build(clock)

            outboxRepository.save(outboxRecord)
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
        public OrderService(OrderRepository orderRepository, OutboxRecordRepository outboxRepository, ObjectMapper objectMapper, Clock clock) {
            this.orderRepository = orderRepository;
            this.outboxRepository = outboxRepository;
            this.objectMapper = objectMapper;
            this.clock = clock;
        }
        @Transactional
        public Order createOrder(CreateOrderCommand command) {
            Order order = Order.create(command);
            orderRepository.save(order);
            OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), ...);
            OutboxRecord outboxRecord = new OutboxRecord.Builder()
                .aggregateId(order.getId().toString())
                .eventType("OrderCreated")
                .payload(objectMapper.writeValueAsString(event))
                .build(clock);
            outboxRepository.save(outboxRecord);
            return order;
        }
    }
    ```

**Tip:**
- By default, `publish-after-save: true` ensures that events are not only stored in the outbox, but also published to all listeners in the same transaction. This is useful if you want to react to events immediately within your application (e.g. for projections or side effects), in addition to externalizing them via the outbox. If you only want to store events for external processing, set `publish-after-save: false`.

**Key Benefits:**
- All approaches ensure events are stored and published in the same transaction as your business data.
- Outbox records are only created if the transaction commits successfully.
- You can use either approach, or all three, depending on your domain model and preferences.

## Configuration Overview

Configure the outbox behavior in your `application.yml`:

```yaml
outbox:
  # Polling interval for processing events (milliseconds)
  poll-interval: 2000                # Interval in milliseconds at which the outbox is polled (default: 2000)

  # Batch size for processing events
  batch-size: 10                     # Maximum number of aggregate IDs to process in a single batch (default: 10)

  # Schema initialization
  schema-initialization:
    enabled: true

  # Processing behavior configuration
  processing:
    stop-on-first-failure: true      # Whether to stop processing remaining events in an aggregate when one event fails (default: true)
    publish-after-save: true         # Whether to publish events to listeners after saving them to the outbox (default: true)
    delete-completed-records: false  # If true, completed outbox records will be deleted after processing (default: false)
    executor-core-pool-size: 4       # Core pool size for the ThreadPoolTaskExecutor (default: 4)
    executor-max-pool-size: 8        # Maximum pool size for the ThreadPoolTaskExecutor (default: 8)

  # Instance coordination and partition management
  instance:
    graceful-shutdown-timeout-seconds: 15      # Timeout in seconds for graceful shutdown (default: 15)
    stale-instance-timeout-seconds: 30         # Timeout in seconds to consider an instance stale (default: 30)
    heartbeat-interval-seconds: 5              # Interval in seconds between instance heartbeats (default: 5)
    new-instance-detection-interval-seconds: 10 # Interval in seconds for detecting new instances (default: 10)

  # Retry configuration
  retry:
    max-retries: 3                # Maximum number of retry attempts for failed outbox events (default: 3)
    policy: "exponential"         # Retry policy strategy: fixed, exponential, or jittered (default: exponential)

    # Exponential backoff configuration
    exponential:
      initial-delay: 2000         # Initial delay in ms for exponential backoff (default: 2000)
      max-delay: 60000            # Maximum delay in ms for exponential backoff (default: 60000)
      multiplier: 2.0             # Multiplier for exponential backoff (default: 2.0)

    # Fixed delay configuration
    fixed:
      delay: 5000                 # Fixed delay in ms between retry attempts (default: 5000)

    # Jittered retry configuration (adds randomness to base policy)
    jittered:
      base-policy: exponential    # Base retry policy for jittered retry (default: exponential)
      jitter: 500                 # Maximum random jitter in ms to add to the base policy's delay (default: 500)
```
