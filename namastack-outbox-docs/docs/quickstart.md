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

Namastack Outbox for Spring Boot uses a Clock for reliable, testable timestamps.

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

The library will create all required tables and indices on startup.

!!! info "Manual Schema Creation"
    If you prefer to manage the database schema manually, see the [Database Configuration](features.md#database-configuration) section in the Features guide for SQL DDL statements.

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

With **Namastack Outbox v0.3.0+**, publishing events is as simple as annotating your event class and using Spring's `@DomainEvents`:

=== "Kotlin"

    ```kotlin
    // Step 1: Annotate your event with @OutboxEvent
    @OutboxEvent(aggregateId = "orderId")
    data class OrderCreatedEvent(
        val orderId: String,
        val customerId: String,
        val amount: BigDecimal
    ) : DomainEvent
    
    // Step 2: Use @DomainEvents in your aggregate root
    @Entity
    @Table(name = "orders")
    class Order(
        @Id
        val id: String,
        val customerId: String,
        val amount: BigDecimal
    ) : AbstractAggregateRoot<Order>() {
        
        companion object {
            fun create(command: CreateOrderCommand): Order {
                val order = Order(
                    id = UUID.randomUUID().toString(),
                    customerId = command.customerId,
                    amount = command.amount
                )
                order.registerEvent(OrderCreatedEvent(order.id, order.customerId, order.amount))
                return order
            }
        }
    }
    
    // Step 3: Save via repository - events are automatically handled
    @Service
    class OrderService(private val orderRepository: OrderRepository) {
    
        fun createOrder(command: CreateOrderCommand): Order {
            val order = Order.create(command)
            orderRepository.save(order)  // @DomainEvents -> @OutboxEvent -> Outbox
            return order
        }
    }
    ```

=== "Java"

    ```java
    // Step 1: Annotate your event with @OutboxEvent
    @OutboxEvent(aggregateId = "orderId")
    public record OrderCreatedEvent(
        String orderId,
        String customerId,
        BigDecimal amount
    ) implements DomainEvent {}
    
    // Step 2: Use @DomainEvents in your aggregate root
    @Entity
    @Table(name = "orders")
    public class Order extends AbstractAggregateRoot<Order> {
        @Id
        private String id;
        private String customerId;
        private BigDecimal amount;
        
        public Order(String id, String customerId, BigDecimal amount) {
            this.id = id;
            this.customerId = customerId;
            this.amount = amount;
        }
        
        public static Order create(CreateOrderCommand command) {
            Order order = new Order(
                UUID.randomUUID().toString(),
                command.getCustomerId(),
                command.getAmount()
            );
            order.registerEvent(new OrderCreatedEvent(order.id, order.customerId, order.amount));
            return order;
        }
        
        // Getters...
    }
    
    // Step 3: Save via repository - events are automatically handled
    @Service
    public class OrderService {
        private final OrderRepository orderRepository;
        
        public OrderService(OrderRepository orderRepository) {
            this.orderRepository = orderRepository;
        }
        
        public Order createOrder(CreateOrderCommand command) {
            Order order = Order.create(command);
            orderRepository.save(order);  // @DomainEvents -> @OutboxEvent -> Outbox
            return order;
        }
    }
    ```

!!! success "That's it!"
    Your events are now:

    - ✅ Automatically serialized to JSON (Jackson)
    - ✅ Persisted to the outbox database
    - ✅ Stored atomically with your business data
    - ✅ Processed and published reliably

## Alternative: Manual Outbox Record Creation

If you prefer more control or don't want to use `@DomainEvents`, you can manually create and persist outbox records:

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
                .aggregateId(order.id)
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
            Order order = Order.create(command);
            orderRepository.save(order);

            OrderCreatedEvent event = new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getAmount());
            OutboxRecord record = new OutboxRecord.Builder()
                    .aggregateId(order.getId())
                    .eventType("OrderCreated")
                    .payload(objectMapper.writeValueAsString(event))
                    .build(clock);

            outboxRepository.save(record);
            return order;
        }
    }
    ```

!!! note "When to Use Manual Creation"
    Use the manual approach when:

    - You need explicit control over which events are persisted
    - You're working with legacy code that doesn't use aggregate roots
    - You want to persist custom metadata alongside events
    - You prefer explicit over implicit behavior

## Configuration Overview

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
    publish-after-save: true     # Publish events to listeners after saving to outbox (default: true)

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
