# Quickstart

**Namastack Outbox for Spring Boot** is a robust library for **Java and Kotlin** projects that implements the **Transactional Outbox Pattern** for reliable record publishing in distributed systems.

This guide will get you up and running in 5 minutes with minimal configuration.

---

## Add Dependency

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

## Enable Outbox

Annotate your application class with `@EnableOutbox`:

=== "Kotlin"

    ```kotlin
    @SpringBootApplication
    @EnableOutbox
    @EnableScheduling
    class Application

    fun main(args: Array<String>) {
        runApplication<Application>(*args)
    }
    ```

=== "Java"

    ```java
    @SpringBootApplication
    @EnableOutbox
    @EnableScheduling
    public class Application {
        public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
        }
    }
    ```

## Create a Handler

Create a typed handler for your payload:

=== "Kotlin"

    ```kotlin
    @Component
    class OrderRecordHandler : OutboxTypedHandler<OrderCreatedRecord> {
        override fun handle(payload: OrderCreatedRecord) {
            // Process the record - this will be called automatically when ready
            eventPublisher.publish(payload)
        }
    }
    ```

=== "Java"

    ```java
    @Component
    public class OrderRecordHandler implements OutboxTypedHandler<OrderCreatedRecord> {
        @Override
        public void handle(OrderCreatedRecord payload) {
            // Process the record - this will be called automatically when ready
            eventPublisher.publish(payload);
        }
    }
    ```

Or use annotations for multiple handlers:

=== "Kotlin"

    ```kotlin
    @Component
    class MyHandlers {
        @OutboxHandler
        fun handleOrderCreated(payload: OrderCreatedRecord) {
            // ...
        }

        @OutboxHandler
        fun handlePaymentProcessed(payload: PaymentProcessedRecord) {
            // ...
        }
    }
    ```

=== "Java"

    ```java
    @Component
    public class MyHandlers {
        @OutboxHandler
        public void handleOrderCreated(OrderCreatedRecord payload) {
            // ...
        }

        @OutboxHandler
        public void handlePaymentProcessed(PaymentProcessedRecord payload) {
            // ...
        }
    }
    ```

## Schedule Records Atomically

=== "Kotlin"

    ```kotlin
    @Service
    class OrderService(
        private val outbox: Outbox,
        private val orderRepository: OrderRepository
    ) {
        @Transactional
        fun createOrder(command: CreateOrderCommand) {
            val order = Order.create(command)
            orderRepository.save(order)
            
            // Schedule record - saved atomically with the order
            outbox.schedule(
                payload = OrderCreatedRecord(order.id, order.customerId),
                key = "order-${order.id}"  // Groups records for ordered processing
            )
        }
    }
    ```

=== "Java"

    ```java
    @Service
    public class OrderService {
        private final Outbox outbox;
        private final OrderRepository orderRepository;

        public OrderService(Outbox outbox, OrderRepository orderRepository) {
            this.outbox = outbox;
            this.orderRepository = orderRepository;
        }

        @Transactional
        public void createOrder(CreateOrderCommand command) {
            Order order = Order.create(command);
            orderRepository.save(order);
            
            // Schedule record - saved atomically with the order
            outbox.schedule(
                new OrderCreatedRecord(order.getId(), order.getCustomerId()),
                "order-" + order.getId()  // Groups records for ordered processing
            );
        }
    }
    ```

## Alternative: Using Spring's ApplicationEventPublisher

If you prefer Spring's native event publishing, annotate your events with `@OutboxEvent`:

=== "Kotlin"

    ```kotlin
    @OutboxEvent(key = "#event.orderId")  // SpEL expression for key resolution
    data class OrderCreatedEvent(
        val orderId: String,
        val customerId: String,
        val amount: BigDecimal
    )

    @Service
    class OrderService(
        private val orderRepository: OrderRepository,
        private val eventPublisher: ApplicationEventPublisher
    ) {
        @Transactional
        fun createOrder(command: CreateOrderCommand) {
            val order = Order.create(command)
            orderRepository.save(order)
            
            // Publish event - automatically saved to outbox atomically
            eventPublisher.publishEvent(
                OrderCreatedEvent(order.id, order.customerId, order.amount)
            )
        }
    }
    ```

=== "Java"

    ```java
    @OutboxEvent(key = "#event.orderId")  // SpEL expression for key resolution
    public class OrderCreatedEvent {
        private String orderId;
        private String customerId;
        private BigDecimal amount;
        // constructor, getters...
    }

    @Service
    public class OrderService {
        private final OrderRepository orderRepository;
        private final ApplicationEventPublisher eventPublisher;

        public OrderService(OrderRepository orderRepository, 
                           ApplicationEventPublisher eventPublisher) {
            this.orderRepository = orderRepository;
            this.eventPublisher = eventPublisher;
        }

        @Transactional
        public void createOrder(CreateOrderCommand command) {
            Order order = Order.create(command);
            orderRepository.save(order);
            
            // Publish event - automatically saved to outbox atomically
            eventPublisher.publishEvent(
                new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getAmount())
            );
        }
    }
    ```

## Configure (Optional)

=== "YAML"

    ```yaml
    outbox:
      poll-interval: 2000
      batch-size: 10
      retry:
        policy: "exponential"
        max-retries: 3
        exponential:
          initial-delay: 1000
          max-delay: 60000
          multiplier: 2.0
    ```

=== "Properties"

    ```properties
    outbox.poll-interval=2000
    outbox.batch-size=10
    outbox.retry.policy=exponential
    outbox.retry.max-retries=3
    outbox.retry.exponential.initial-delay=1000
    outbox.retry.exponential.max-delay=60000
    outbox.retry.exponential.multiplier=2.0
    ```

For a complete list of all configuration options, see the [Configuration Reference](features.md#configuration-reference).

---

**That's it!** Your records are now reliably persisted and processed.

## Key Features

- ✅ **Transactional Atomicity**: Records saved in same transaction as domain data
- ✅ **Automatic Retry**: Exponential backoff, fixed delay, or jittered policies
- ✅ **Ordered Processing**: Records with same key processed sequentially
- ✅ **Handler-Based**: Annotation-based or interface-based handler registration
- ✅ **Horizontal Scaling**: Automatic partition assignment across instances
- ✅ **Zero Message Loss**: Database-backed with at-least-once delivery
- ✅ **Type-Safe Handlers**: Generic or typed handler support
- ✅ **Built-in Metrics**: Micrometer integration for monitoring
- ✅ **Flexible Payloads**: Store any type - records, commands, notifications, etc.

## Supported Databases

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server

## Next Steps

- Explore the [Features Guide](features.md) for advanced capabilities
- Check out the [API Reference](https://javadoc.io/doc/io.namastack/namastack-outbox-core) for detailed documentation
- Join [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions) for community support

