# Namastack Outbox for Spring Boot Basic Example

This example demonstrates the usage of the **Namastack Outbox for Spring Boot** library for 
implementing the transactional outbox pattern in a Spring Boot application.

## 🚀 What This Example Shows

This demo showcases a **Customer Management System** that reliably publishes domain event using the
transactional outbox pattern:

- **Transactional Event Publishing**: All events are published within database transactions
- **Event Processing**: Custom processors handle different customer lifecycle events
- **Aggregate Ordering**: Events for the same customer are processed in order
- **Retry Mechanisms**: Failed events are automatically retried with exponential backoff
- **External Service Integration**: Simulates real-world integrations (Email, CRM, Analytics)

## 📁 Project Structure

```
src/main/kotlin/io/namastack/demo/
├── DemoApplication.kt                    # Main Spring Boot application with demo runner
├── DemoRecordProcessor.kt                # Event processor that handles all events
├── DomainEvent.kt                        # Base domain event interface
└── customer/
    ├── Customer.kt                       # Customer entity with lifecycle methods
    ├── CustomerService.kt                # Business service with outbox event publishing
    ├── CustomerRepository.kt             # JPA repository for customer persistence
    ├── CustomerRegisteredEvent.kt        # Domain event for customer registration
    ├── CustomerActivatedEvent.kt         # Domain event for customer activation
    └── CustomerDeactivatedEvent.kt       # Domain event for customer deactivation
```

## 🏃‍♂️ Running the Example

### Prerequisites

- Java 21 or later
- Gradle (or use the included wrapper)

### Start the Application

```bash
./gradlew bootRun
```

### Watch the Demo

The application automatically demonstrates:

- **Customer Registration** → Welcome emails, CRM integration
- **Customer Activation** → Notification services, analytics tracking
- **Customer Deactivation** → Cleanup services, audit logging

## 🎯 Key Features Demonstrated

### 1. **Transactional Safety**

```kotlin
@Transactional
fun register(firstName: String, lastName: String): Customer {
    val customer = Customer.register(firstName, lastName)
    customerRepository.save(customer)

    // Event is saved in the same transaction
    outboxRecordRepository.save(createOutboxRecord(customerRegisteredEvent))

    return customer
}
```

### 2. **Event Processing**

```kotlin
override fun process(record: OutboxRecord) {
    when (record.eventType) {
        "CustomerRegisteredEvent" -> {
            // Send welcome email
            // Create CRM profile
            // Track analytics
        }
    }
}
```

### 3. **Retry with Exponential Backoff**

Failed events are automatically retried with:

- **3 retry attempts** maximum
- **2-second initial delay**
- **2x backoff multiplier**
- **Jitter** to prevent thundering herd

### 4. **Failure Simulation**

The demo processor randomly fails ~10% of external service calls to demonstrate retry behavior.

## 🔧 Configuration

Key settings in `application.yml`:

```yaml
outbox:
  poll-interval: 2000
  schema-initialization:
    enabled: true
  locking:
    refresh-threshold: 2
    extension-seconds: 5
  retry:
    policy: jittered
    max-retries: 3
    jittered:
      base-policy: exponential
      jitter: 1000
    exponential:
      initial-delay: 1000
      max-delay: 60000
      multiplier: 2.0
  processing:
    stop-on-first-failure: true
```

## 🧪 What to Observe

1. **Event Ordering**: Multiple events for the same customer are processed sequentially
2. **Retry Behavior**: Watch failed events get retried with increasing delays
3. **Transactional Safety**: Events are never lost, even if processing fails
4. **External Service Calls**: See simulated integrations in the logs
5. **Performance**: Batch processing with configurable intervals

## 🔍 Learn More

- [Main Documentation](https://outbox.namastack.io/)

