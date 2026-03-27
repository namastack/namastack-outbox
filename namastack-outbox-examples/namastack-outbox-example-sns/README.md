# namastack-outbox-example-sns

Demonstrates externalizing outbox records to **AWS SNS** using the `namastack-outbox-sns` integration module with [LocalStack](https://localstack.cloud/) for local development.

## What this example shows

- Configuring `SnsOutboxRouting` with type-based routing to different SNS topic ARNs
- Sending custom SNS message attributes (headers) per event type
- Using the default topic ARN fallback for unmatched payload types
- Running against LocalStack for local AWS SNS emulation

## Prerequisites

- Docker (for LocalStack)
- Java 21+

## Running the example

**1. Start LocalStack:**

```bash
docker-compose up -d
```

**2. Run the application:**

```bash
./gradlew bootRun
```

The demo registers two customers and then removes them, publishing SNS notifications for each operation.

## Configuration

```yaml
spring.cloud.aws:
  region:
    static: us-east-1
  credentials:
    access-key: test
    secret-key: test
  sns:
    endpoint: http://localhost:4566   # LocalStack endpoint

namastack:
  outbox:
    sns:
      default-topic-arn: arn:aws:sns:us-east-1:000000000000:customers
```

## Routing

`CustomerRegisteredEvent` → `arn:aws:sns:us-east-1:000000000000:customer-registrations`
All other events → `arn:aws:sns:us-east-1:000000000000:default-topic`

