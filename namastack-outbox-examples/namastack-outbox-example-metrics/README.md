# Namastack Outbox - Metrics Example

This example exports outbox metrics through Spring Boot Actuator and Prometheus. It uses
PostgreSQL so you can pause the database and see that metric scrapes remain responsive.

## What This Example Shows

- Record counts for pending (`new`), completed, and failed messages
- Timers for scheduling, delivery attempts, and processing
- Background collection of database-backed gauges, including freshness tracking
- JVM metrics and an application counter that remain available during database outages
- Automatic recovery of outbox gauges when PostgreSQL becomes available again

## Running the Example

You need Java 17 or later and Docker. To use changes from this checkout, first publish the
snapshot dependencies from the repository root:

```bash
./gradlew publishToMavenLocal -x test
```

Then start the example:

```bash
cd namastack-outbox-examples/namastack-outbox-example-metrics
docker compose up -d --wait
./gradlew bootRun
```

| Service | Address |
|---------|---------|
| Application | http://localhost:8085 |
| Prometheus scrape endpoint | http://localhost:8085/actuator/prometheus |
| Prometheus UI | http://localhost:9095 |
| PostgreSQL | `localhost:5440` (database `outbox_metrics`, user `username`, password `password`) |

Prometheus scrapes the application every two seconds with a one-second timeout. The Compose
configuration connects to the application running on your host via `host.docker.internal`.

## Generate Metrics

In another terminal, enqueue successful messages and messages that deliberately fail:

```bash
curl -X POST 'http://localhost:8085/messages?count=10'
curl -X POST 'http://localhost:8085/messages?count=3&fail=true'
curl http://localhost:8085/ping
curl http://localhost:8085/actuator/prometheus
```

`POST /messages` accepts a count from 1 to 1000 and returns HTTP 202 after scheduling the
messages in a transaction. Delivery happens asynchronously. Failing messages remain pending
while retries run, then become failed. The retry delay is ten seconds. Completed records are
retained so their count stays visible.

Open the Prometheus UI and try these queries:

```promql
outbox_records
```

```promql
outbox_record_attempt_seconds_count
```

```promql
outbox_record_process_seconds_count
```

```promql
time() - outbox_metrics_collection_last_success_timestamp
```

```promql
demo_pings_total
```

Record counts have an `outbox_record_status` label (`new`, `completed`, or `failed`). Counts
are refreshed in the background, so they do not change immediately after a request. The last
success timestamp advances only after every database-backed gauge has been collected successfully.

## Test a Database Outage

Wait until `outbox_records` has numeric values, then run these commands from the example directory:

```bash
docker compose pause database
curl http://localhost:8085/ping
curl --max-time 1 http://localhost:8085/actuator/prometheus
```

Pausing PostgreSQL leaves connections open but prevents it from answering queries. This tests
the case where a database query is waiting for a response. You can also use
`docker compose stop database` to test a disconnected database.

During the outage:

- The scrape endpoint continues returning HTTP 200 within the one-second timeout.
- Database-backed gauges become `NaN` when collection fails or their cached values expire.
  With this example's settings, wait at least 15 seconds to see all cached values expire.
- JVM metrics remain available, and each call to `/ping` increases `demo_pings_total`.
- `outbox_metrics_collection_last_success_timestamp` stops advancing.
- `up{job="outbox-metrics"}` stays `1` in Prometheus. This means scraping works; it does not
  indicate that the database is healthy.

Prometheus may show gaps for `NaN` values. Inspect the raw `/actuator/prometheus` response to
see them directly. Requests to enqueue messages still require the database, and database
connection errors in the application logs are expected during this experiment.

Resume the database:

```bash
docker compose unpause database
```

If you stopped it instead, use `docker compose start database`. Allow time for connections to
recover and the next collection to finish. The gauges return to numeric values and the last
success timestamp advances without restarting the application.

## Configuration

The example shortens the default collection timings in `application.yml`:

```yaml
namastack:
  outbox:
    observability:
      metrics:
        refresh-interval: 5s
        max-age: 15s
```

`refresh-interval` is the delay after a collection completes. `max-age` limits how long a
successful value can be returned. JDBC connection and socket timeouts also bound database
waits; scrape requests read the cached values and do not wait for those queries.

## Tests

```bash
./gradlew test
```

The smoke tests start their own PostgreSQL container and use a random HTTP port. They verify
successful and failed message metrics, HTTP scraping while PostgreSQL is paused, stale values,
and recovery after resuming PostgreSQL. Docker must be running; the Compose stack is not required.

## Cleanup

Stop the application with Ctrl+C, then remove the example containers and their data:

```bash
docker compose unpause database
docker compose down -v
```

The unpause command is only needed if the database is still paused.
