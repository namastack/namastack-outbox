# Namastack Outbox - Retry Policy Example

This example demonstrates **retry policy configuration** and automatic retry behavior for failed outbox processing.

## What This Example Shows

- Automatic retry mechanism for failed handlers
- Configurable retry policies (fixed delay, linear backoff, exponential backoff)
- Maximum retry attempts configuration
- Detailed trace logging for retry behavior observation

## Key Components

- **DemoOutboxHandler**: Handler with simulated random failures (30% failure rate)
- **Retry Configuration**: Configured in `application.yml`
- **Trace Logging**: Enabled for detailed retry behavior observation

## Retry Behavior

When a handler fails:
1. The exception is caught and logged
2. The record's failure count is incremented
3. Next retry time is calculated based on the retry policy
4. The record is rescheduled for processing
5. Process repeats until success or max retries exceeded

## Testing retries

Use a Spring Boot integration test to exercise the outbox scheduler, persistence, and handler
together. [`ExampleRetryTest`](src/test/kotlin/io/namastack/demo/ExampleRetryTest.kt) uses the JDBC
starter with an isolated H2 database and a test handler that fails once.

1. Schedule one event with a unique ID in a transaction and commit it. The asynchronous
   worker can see the record after the transaction commits.
2. Record the metadata in the test handler on each invocation. Throw on the first call and
   record the successfully handled payload on the second.
3. Use Awaitility with a timeout to wait for the payload with that event ID. After it arrives,
   assert that both invocations share a non-blank `recordId`, that `attempt` is `1` then `2`,
   and that `isRetry` is `false` then `true`.

The test uses public handler metadata and waits for an observable outcome. For an application
integration test, the outcome could be a committed database change or a call to a fake downstream
service.

Use JDK 21 to run Gradle and make JDK 17 available for the build toolchain.
From the repository root, publish the snapshot and run the example test:

```bash
./gradlew publishToMavenLocal -x test --no-daemon
cd namastack-outbox-examples/namastack-outbox-example-retry
./gradlew test --tests io.namastack.demo.ExampleRetryTest
```

## Running the Example

```bash
./gradlew :namastack-outbox-example-retry:bootRun
```

The application will:
1. Register multiple customers
2. Schedule outbox records
3. Process records with random failures (30% rate)
4. Automatically retry failed records
5. Log detailed retry information

## Configuration

See `application.yml` for:
- Retry policy type (fixed/linear/exponential)
- Retry delay configuration
- Maximum retry attempts
- Trace logging enabled for detailed output

## Expected Output

Watch the logs to see:
- Initial processing attempts
- Failure messages and exceptions
- Retry scheduling and execution
- Success after retries
- Final completion or permanent failure after max retries

This example is crucial for understanding how the outbox pattern handles transient failures in production environments.

## Stable handler IDs

The handlers define responsibility-based IDs that remain stable across class, method, and package
renames:

- `customers.send-registration-email` for the typed customer-registration handler
- `events.publish-to-external-broker` for the generic event publisher
