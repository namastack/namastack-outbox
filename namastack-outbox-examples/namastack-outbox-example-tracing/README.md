# Namastack Outbox - Distributed Tracing Example

This example demonstrates **distributed tracing integration** with the Namastack Outbox pattern
using OpenTelemetry and Micrometer Tracing.

## What This Example Shows

- Automatic trace context propagation across async boundaries
- OpenTelemetry integration with Spring Boot
- W3C Trace Context format (`traceparent`, `tracestate`, `baggage`)
- Span creation for outbox record processing — **zero handler code changes required**
- Trace visualization with Grafana

## Key Components

- **`namastack-outbox-tracing`**: Auto-configures everything — captures trace context at
  scheduling time and restores it as a child span at processing time
- **Grafana LGTM Stack**: For trace visualization (Loki, Grafana, Tempo, Mimir)

## How It Works

### 1. Trace Context Capture (Producer Side)

When scheduling an outbox record:
```kotlin
outbox.schedule(payload, key)
```

`OutboxTracingContextProvider` (auto-registered by `namastack-outbox-tracing`) automatically:
- Reads the current span context from the active Micrometer `Tracer`
- Serializes it to W3C Trace Context format (`traceparent`, `tracestate`, `baggage`)
- Stores it in the record's `context` map, which is persisted to the database

### 2. Trace Context Restoration (Consumer Side)

When processing an outbox record, `namastack-outbox-tracing` automatically:
- Reads the W3C trace headers stored in `record.context`
- Creates a child span linked to the original producer span via a Micrometer Observation
- Attaches outbox-specific tags (`outbox.handler.kind`, `outbox.handler.id`, `outbox.record.id`,
  `outbox.record.key`, `outbox.delivery.attempt`)

Handlers receive the restored trace context with no extra code:
```kotlin
@Component
class CustomerRegisteredOutboxHandler : OutboxTypedHandler<CustomerRegisteredEvent> {
    override fun handle(payload: CustomerRegisteredEvent, metadata: OutboxRecordMetadata) {
        // The active span is already a child of the original producer span.
        // No manual span creation needed.
        ExternalMailService.send(payload.email)
    }
}
```

## Prerequisites

Start the Grafana LGTM stack:

```bash
docker compose up -d
```

This provides:
- **Grafana** on http://localhost:3000 (for visualization)
- **OpenTelemetry Collector** on ports 4317 (gRPC) and 4318 (HTTP)

## Running the Example

1. Start Grafana LGTM:
   ```bash
   docker compose up -d
   ```

2. Run the application:
   ```bash
   ./gradlew :namastack-outbox-example-tracing:bootRun
   ```

3. Trigger the endpoint:
   ```bash
   curl http://localhost:8080/
   ```

4. View traces in Grafana:
   - Open http://localhost:3000
   - Navigate to Explore → Tempo
   - Search for traces

## What You'll See in Grafana

A complete trace showing:
1. **HTTP Request Span**: Incoming web request
2. **Service Method Span**: `customer.register` operation
3. **Database Transaction Span**: JPA/Hibernate operations
4. **Outbox Schedule Span**: Record creation and context serialization
5. **Async Barrier**: Trace headers stored in the database alongside the record
6. **Outbox Process Span**: Child span restored automatically by `namastack-outbox-tracing`

The trace demonstrates that despite the async boundary, the entire flow is connected.

## Configuration

See `application.yml` for:
- OpenTelemetry exporter configuration
- Trace sampling settings (100% for demo)
- Log correlation pattern with `traceId` and `spanId`

## Key Takeaways

- ✅ Trace context survives async boundaries via database storage
- ✅ Full distributed trace visibility across the outbox pattern
- ✅ OpenTelemetry standard compliance (W3C Trace Context)
- ✅ Zero code changes needed in handlers — tracing is fully automatic
- ✅ Production-ready observability for microservices
