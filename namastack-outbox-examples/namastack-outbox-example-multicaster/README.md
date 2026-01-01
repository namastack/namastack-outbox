# Namastack Outbox - Multicaster Example

This example demonstrates **isolation between Spring application events and outbox transactions**.

## What This Example Shows

- Outbox transaction isolation from application event listeners
- `@Async` event listeners do not affect outbox behavior
- Exceptions in event listeners do not rollback outbox records
- Clean separation of concerns between events and transactional outbox

## Key Components

- **DemoEventListener**: Async event listener that always throws exceptions
- **DemoOutboxHandler**: Outbox handler with random failures (30% rate)
- **CustomerService**: Publishes Spring events AND schedules outbox records
- **@EnableAsync**: Enables async event processing

## What Happens

```kotlin
@Service
class CustomerService {
    @Transactional
    fun register(...) {
        // 1. Save customer to database
        customerRepository.save(customer)
        
        // 2. Publish Spring application event (async)
        applicationEventPublisher.publish(event)
        
        // 3. Schedule outbox record (transactional)
        outbox.schedule(event)
    }
}
```

The async event listener throws an exception, but:
- ✅ The database transaction commits successfully
- ✅ The outbox record is persisted
- ✅ The outbox handler processes the record independently
- ❌ The event listener exception does NOT affect outbox processing

## Running the Example

```bash
./gradlew :namastack-outbox-example-multicaster:bootRun
```

The application will:
1. Register three customers
2. Publish Spring events (will fail asynchronously)
3. Schedule outbox records (transactional)
4. Process outbox records independently
5. Demonstrate that event failures don't affect outbox

## Expected Output

Watch the logs to see:
- Customer registration succeeds
- Async event listener exceptions (ignored)
- Outbox records processed successfully
- Clear separation between event and outbox processing

This example proves that outbox processing is isolated from application events and their potential failures.

