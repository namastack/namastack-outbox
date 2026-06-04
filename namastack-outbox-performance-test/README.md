# Namastack Outbox Performance Test

This standalone Gradle composite build measures Namastack Outbox throughput against PostgreSQL.
The primary `steady-state` mode continuously inserts payment events and verifies whether consumers
can keep up without a growing backlog. The `backlog-drain` mode measures recovery after downtime or
a burst.

## Prerequisites

- Java 17
- Docker with Docker Compose
- `curl`

## Primary Test

```bash
./run-performance-test.sh \
  --mode=steady-state \
  --instances=4 \
  --producer-rate=3000 \
  --producer-duration=10m \
  --producer-workers=4 \
  --producer-batch-size=1 \
  --records-per-key=1 \
  --warmup-records=25000
```

The producer plans exactly `producer-rate × producer-duration` records. Records are never silently
dropped when the local producer cannot keep up. Instead, production takes longer and the report
marks the producer result as `TARGET MISSED`.

`--producer-batch-size=1` models one event per committed business transaction. Increase the value
for import-style workloads. `--producer-workers` controls the number of parallel JDBC connections.

## Event And Key Model

Each generated record contains a small `PaymentRequestedEvent` payload with a payment ID, customer
ID, amount, currency and creation timestamp. The default `--records-per-key=1` assigns one
deterministic `payment:<id>` key per record. This is the baseline for maximum independent
parallelism and is reproducible across runs.

Use values such as `--records-per-key=10` or `--records-per-key=100` as separate scenarios to
measure the effect of ordered processing for repeated keys. Random UUID keys are not needed for the
baseline: deterministic keys are realistic enough for hashing while making test runs comparable.

## Tooling Architecture

The command-line tooling uses small packages with explicit responsibilities. The root package only
contains the entry point and dependency wiring.

```text
command -> run.PerformanceTestService -> store.PerformanceStore <- jdbc
collector ---------------------------> store.PerformanceStore <- jdbc
collector -> report.PerformanceReportWriter <- report.MarkdownPerformanceReportWriter
```

- `command` parses command-line arguments, creates request objects and prints results.
- `run` contains the performance-test lifecycle, requests, results and run state.
- `record` creates deterministic outbox benchmark records.
- `store` defines database access ports.
- `collector` samples run state and coordinates report creation.
- `jdbc` contains SQL, JDBC connections and PostgreSQL COPY implementations.
- `report` contains measurement models and Markdown, JSON, CSV, Prometheus and SVG reporting.
- `internal` contains small module-internal utility functions.

## Results

Steady-state reports separate three questions:

- `Correctness result`: Were all records processed after the final drain without retries or errors?
- `Producer rate result`: Did the local producer reach the requested write rate?
- `Capacity assessment`: Did consumers keep up while the target rate was actually applied?

The capacity assessment uses stabilized backlog growth after `--measurement-warmup=30s` and the
remaining backlog at producer stop. Limits can be overridden with `--max-backlog-growth-rate` and
`--max-end-backlog`.

## Recovery Test

```bash
./run-performance-test.sh \
  --mode=backlog-drain \
  --instances=4 \
  --records=1000000 \
  --records-per-key=1
```

## Reports And Monitoring

Each run creates a self-contained Markdown report:

```text
reports/<run-id>/
├── report.md
├── parameters.json
├── samples.csv
├── prometheus-queries.json
├── collector.log
├── graphs/
└── logs/
```

Use `--keep-monitoring=true` to leave PostgreSQL, Prometheus and Grafana running. Grafana is
available at `http://localhost:3000/d/namastack-outbox-performance`.
