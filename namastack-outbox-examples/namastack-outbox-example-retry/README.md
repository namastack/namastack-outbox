# Namastack Outbox - Retry Policy Example

This example demonstrates **retry policy configuration** and automatic retry behavior for failed outbox processing.

## What This Example Shows

- Automatic retry mechanism for failed handlers
- Configurable retry policies (fixed delay, exponential backoff)
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
- Retry policy type (fixed/exponential)
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

