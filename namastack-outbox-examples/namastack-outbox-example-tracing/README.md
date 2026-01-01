# Namastack Outbox - Distributed Tracing Example

This example demonstrates **distributed tracing integration** with the Namastack Outbox pattern using OpenTelemetry and Micrometer Tracing.

## What This Example Shows

- Automatic trace context propagation across async boundaries
- OpenTelemetry integration with Spring Boot
- W3C Trace Context format (traceparent, tracestate, baggage)
- Span creation for outbox record processing
- Trace visualization with Grafana

## Key Components

- **OutboxTracingContextProvider**: Captures current trace context and stores it in outbox records
- **HandlerSpanFactory**: Restores trace context and creates child spans for processing
- **SpanUtils**: Utility for executing code within span context
- **Grafana LGTM Stack**: For trace visualization (Loki, Grafana, Tempo, Mimir)

## How It Works

### 1. Trace Context Capture (Producer Side)

When scheduling an outbox record:
```kotlin
outbox.schedule(payload, key)
```

The `OutboxTracingContextProvider` automatically:
- Extracts current trace context (traceId, spanId)
- Serializes it to W3C format
- Stores it in the record's context map

### 2. Trace Context Restoration (Consumer Side)

When processing an outbox record:
```kotlin
val span = handlerSpanFactory.create("operation name", metadata)
tracer.runWithSpan(span) {
    // Processing happens within restored trace context
}
```

The `HandlerSpanFactory`:
- Extracts W3C trace context from record
- Creates a child span linked to the producer span
- Makes the trace continuous across the async boundary

## Prerequisites

Start the Grafana LGTM stack:

```bash
docker-compose up -d
```

This provides:
- **Grafana** on http://localhost:3000 (for visualization)
- **OpenTelemetry Collector** on ports 4317 (gRPC) and 4318 (HTTP)

## Running the Example

1. Start Grafana LGTM:
   ```bash
   docker-compose up -d
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
4. **Outbox Schedule Span**: Record creation
5. **Async Barrier**: Context stored in database
6. **Outbox Process Span**: Record processing (child of schedule span)
7. **Handler Span**: Actual business logic execution

The trace demonstrates that despite the async boundary, the entire flow is connected.

## Configuration

See `application.yml` for:
- OpenTelemetry exporter configuration
- Trace sampling settings (100% for demo)
- Log correlation pattern with traceId and spanId

## Key Takeaways

- ✅ Trace context survives async boundaries via database storage
- ✅ Full distributed trace visibility across the outbox pattern
- ✅ OpenTelemetry standard compliance (W3C Trace Context)
- ✅ Zero code changes needed in handlers (except manual span creation)
- ✅ Production-ready observability for microservices

This example is essential for understanding how to maintain observability in systems using the outbox pattern.

