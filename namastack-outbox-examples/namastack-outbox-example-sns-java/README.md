# namastack-outbox-example-sns-java

Demonstrates externalizing outbox records to **AWS SNS** using the `namastack-outbox-sns` integration module in **pure Java** (no Kotlin), with [LocalStack](https://localstack.cloud/) for local development.

## What This Example Shows

- Externalizing outbox records to SNS topics using Java
- Configuring `SnsOutboxRouting` with the **Java builder API**
- Using `SnsOutboxHandler` for automatic SNS integration
- Routing payloads to different SNS topic ARNs based on payload type
- Setting custom SNS message attributes (headers) per event type
- Running against LocalStack for local AWS SNS emulation

## Key Components

- **`CustomerService`** – Schedules outbox events on customer registration and removal
- **`SnsOutboxRoutingConfiguration`** – Java builder API routing: `CustomerRegisteredEvent` → `customer-registrations`, all others → `default-topic`
- **`SnsOutboxHandler`** – Auto-configured, sends outbox payloads to SNS via `SnsOperations`
- **H2** – In-memory database for outbox storage
- **LocalStack** – Local AWS SNS emulation

## Running the Example

### Prerequisites

Start LocalStack:

```bash
docker-compose up -d
```

### Run

```bash
./gradlew bootRun
```

The demo registers two customers and then removes them, publishing an SNS notification for each operation.

## Configuration

```yaml
spring.cloud.aws:
  region:
    static: us-east-1
  credentials:
    access-key: test
    secret-key: test
  sns:
    endpoint: http://localhost:4566   # LocalStack

namastack:
  outbox:
    sns:
      default-topic-arn: arn:aws:sns:us-east-1:000000000000:customers
```

## Routing

| Payload type              | SNS topic ARN                                                    |
|---------------------------|------------------------------------------------------------------|
| `CustomerRegisteredEvent` | `arn:aws:sns:us-east-1:000000000000:customer-registrations`      |
| All others                | `arn:aws:sns:us-east-1:000000000000:default-topic`               |

