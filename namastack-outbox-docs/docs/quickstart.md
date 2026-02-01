# Quickstart

**Namastack Outbox for Spring Boot** is a robust Spring Boot library for **Java and Kotlin** projects that implements the 
**Transactional Outbox Pattern** for reliable record publishing in distributed systems. Ensures 
records are never lost through atomic persistence and automatic retry logic
with handler-based processing and partition-aware horizontal scaling.

This guide will get you up and running in 5 minutes with minimal configuration.

---

## Add Dependency

=== "Gradle"

    ```kotlin
    dependencies {
        implementation("io.namastack:namastack-outbox-starter-jdbc:{{ outbox_version }}")
    }
    ```

=== "Maven"

    ```xml
    <dependency>
        <groupId>io.namastack</groupId>
        <artifactId>namastack-outbox-starter-jdbc</artifactId>
        <version>{{ outbox_version }}</version>
    </dependency>
    ```

!!! note "JDBC vs JPA"
    We recommend the JDBC starter for quick start as it supports automatic schema creation. For JPA/Hibernate projects, see [JPA Setup](#jpa-setup) below.

## Enable Scheduling

=== "Kotlin"

    ```kotlin
    @SpringBootApplication
    @EnableScheduling  // Required for automatic outbox processing
    class Application

    fun main(args: Array<String>) {
        runApplication<Application>(*args)
    }
    ```

=== "Java"

    ```java
    @SpringBootApplication
    @EnableScheduling  // Required for automatic outbox processing
    public class Application {
        public static void main(String[] args) {
            SpringApplication.run(Application.class, args);
        }
    }
    ```

## Create Handlers

=== "Kotlin"

    ```kotlin
    @Component
    class OrderHandlers {
        // Typed handler - processes specific payload type
        @OutboxHandler
        fun handleOrder(payload: OrderCreatedEvent) {
            eventPublisher.publish(payload)
        }
        
        // Generic handler - processes any payload type
        @OutboxHandler
        fun handleAny(payload: Any, metadata: OutboxRecordMetadata) {
            when (payload) {
                is OrderCreatedEvent -> eventPublisher.publish(payload)
                is PaymentProcessedEvent -> paymentService.process(payload)
                else -> logger.warn("Unknown payload type")
            }
        }
    }
    ```

=== "Java"

    ```java
    @Component
    public class OrderHandlers {
        // Typed handler - processes specific payload type
        @OutboxHandler
        public void handleOrder(OrderCreatedEvent payload) {
            eventPublisher.publish(payload);
        }
        
        // Generic handler - processes any payload type
        @OutboxHandler
        public void handleAny(Object payload, OutboxRecordMetadata metadata) {
            if (payload instanceof OrderCreatedEvent) {
                eventPublisher.publish((OrderCreatedEvent) payload);
            } else if (payload instanceof PaymentProcessedEvent) {
                paymentService.process((PaymentProcessedEvent) payload);
            } else {
                logger.warn("Unknown payload type");
            }
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
            
            // Schedule event - saved atomically with the order
            outbox.schedule(
                payload = OrderCreatedEvent(order.id, order.customerId),
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
            
            // Schedule event - saved atomically with the order
            outbox.schedule(
                new OrderCreatedEvent(order.getId(), order.getCustomerId()),
                "order-" + order.getId()  // Groups records for ordered processing
            );
        }
    }
    ```

## Alternative: Using Spring's ApplicationEventPublisher

If you prefer Spring's native event publishing, annotate your events with `@OutboxEvent`:

=== "Kotlin"

    ```kotlin
    @OutboxEvent(
        key = "#this.orderId",  // SpEL: uses 'orderId' field
        context = [
            OutboxContextEntry(key = "customerId", value = "#this.customerId"),
            OutboxContextEntry(key = "region", value = "#this.region")
        ]
    )
    data class OrderCreatedEvent(
        val orderId: String,
        val customerId: String,
        val region: String,
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
                OrderCreatedEvent(order.id, order.customerId, order.region, order.amount)
            )
        }
    }
    ```

=== "Java"

    ```java
    @OutboxEvent(
        key = "#this.orderId",  // SpEL: uses 'orderId' field
        context = {
            @OutboxContextEntry(key = "customerId", value = "#this.customerId"),
            @OutboxContextEntry(key = "region", value = "#this.region")
        }
    )
    public class OrderCreatedEvent {
        private String orderId;
        private String customerId;
        private String region;
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
                new OrderCreatedEvent(order.getId(), order.getCustomerId(), 
                                     order.getRegion(), order.getAmount())
            );
        }
    }
    ```

Both approaches work equally well. Choose based on your preference:

- **Explicit `outbox.schedule()`**: More control, clearer intent, supports any payload type
- **`@OutboxEvent` + `ApplicationEventPublisher`**: More Spring idiomatic for domain events

## Configure (Optional)

=== "YAML"

    ```yaml
    namastack:
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
    namastack.outbox.poll-interval=2000
    namastack.outbox.batch-size=10
    namastack.outbox.retry.policy=exponential
    namastack.outbox.retry.max-retries=3
    namastack.outbox.retry.exponential.initial-delay=1000
    namastack.outbox.retry.exponential.max-delay=60000
    namastack.outbox.retry.exponential.multiplier=2.0
    ```

For a complete list of all configuration options, see [Configuration Reference](https://outbox.namastack.io/features/#configuration-reference).

**That's it!** Your records are now reliably persisted and processed.

## Key Features

- ✅ **Transactional Atomicity**: Records saved in same transaction as domain data
- ✅ **Zero Message Loss**: Database-backed with at-least-once delivery
- ✅ **Horizontal Scaling**: Automatic partition assignment across instances
- ✅ **Automatic Retry**: Exponential backoff, fixed delay, or jittered policies
- ✅ **Handler-Based**: Annotation-based or interface-based handler registration
- ✅ **Type-Safe Handlers**: Generic or typed handler support
- ✅ **Fallback Handlers**: Graceful degradation when retries are exhausted
- ✅ **Flexible Payloads**: Store any type - events, commands, notifications, etc.
- ✅ **Context Propagation**: Trace IDs, tenant info, correlation IDs across async boundaries
- ✅ **Ordered Processing**: Records with same key processed sequentially
- ✅ **Built-in Metrics**: Micrometer integration for monitoring

## Supported Databases

Any JPA/JDBC-compatible database is supported. Automatic schema creation is available in the JDBC module for:

- ✅ H2 (development)
- ✅ MySQL / MariaDB
- ✅ PostgreSQL
- ✅ SQL Server

**Schema Files for Flyway/Liquibase:**

If you manage your database schema manually, you can find the SQL schema files here:
👉 [Schema Files on GitHub](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema)

---

## JPA Setup

If you prefer using JPA/Hibernate instead of JDBC:

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

!!! warning "Schema Management Required"
    The JPA module does **not** support automatic schema creation. Choose one of these options:

    **Option 1: Hibernate DDL Auto (Development only)**
    ```yaml
    spring:
      jpa:
        hibernate:
          ddl-auto: create  # or create-drop
    ```

    **Option 2: Flyway/Liquibase (Recommended for Production)**
    
    Use the [SQL schema files](https://github.com/namastack/namastack-outbox/tree/main/namastack-outbox-jdbc/src/main/resources/schema) from our repository and configure Hibernate to validate:
    ```yaml
    spring:
      jpa:
        hibernate:
          ddl-auto: validate
    ```

---

## Next Steps

- Explore the [Features Guide](features.md) for advanced capabilities
- Check out the [API Reference](https://javadoc.io/doc/io.namastack/namastack-outbox-api) for detailed documentation
- Report issues at [GitHub Issues](https://github.com/namastack/namastack-outbox/issues)
- Join [GitHub Discussions](https://github.com/namastack/namastack-outbox/discussions) for community support

