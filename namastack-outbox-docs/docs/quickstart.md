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
