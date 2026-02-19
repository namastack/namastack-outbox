# Reliability Guarantees

## What Namastack Outbox for Spring Boot Guarantees

- :material-check-all: **At-least-once delivery**: Records will be processed at least once
- :material-sort-ascending: **Ordering per key**: Records with the same key are processed in order
- :material-backup-restore: **Failure recovery**: System failures don't result in lost records
- :material-scale-balance: **Horizontal scalability**: Multiple instances process different partitions
- :material-shield-lock: **Consistency**: Database transactions ensure data integrity
- :material-clock-check: **Automatic retry**: Failed records are automatically retried
- :material-autorenew: **Automatic rebalancing**: Partitions redistribute on topology changes
- :material-chart-line: **Linear scaling**: Performance scales with instance count

---

## What Namastack Outbox for Spring Boot Does NOT Guarantee

- :material-close: **Exactly-once delivery**: Records may be processed multiple times (handlers should be idempotent)
- :material-close: **Global ordering**: No ordering guarantee across different keys
- :material-close: **Real-time processing**: Records are processed asynchronously with configurable delays

---

!!! tip "Next Steps"
    Ready to get started? Check out the [Quick Start Guide](../quickstart.md) to integrate Namastack Outbox for Spring Boot into your application.

