# countByStatus(status)

Group G4 (periodic, metrics). SQL `countByStatusQuery`; harness `harness/queries/countByStatus.sql`.

```sql
SELECT COUNT(*) FROM outbox_record WHERE status = :status
```

## Callers and frequency
- `namastack-outbox-observability` `OutboxMetricsCollector.collectRecordCounts()`: all statuses
  (`OutboxRecordStatus.entries` = NEW, COMPLETED, FAILED) per refresh, own scheduler, fixed delay
  `refresh-interval` (default 15 s). 3 queries / 15 s / instance.
- Deprecated `namastack-outbox-metrics` `OutboxRecordMetricsMeterBinder`: one gauge per status calling the
  repository on every gauge read (each scrape, each registry, uncached).
- `OutboxInstanceRegistry.getActiveInstanceCount()` -> `instanceRepository.countByStatus(ACTIVE)` is the
  `outbox_instance` table, not this method.
- JPA: `select count(o) ... where o.status = :status`; Mongo: `count({status})` (`status` is `@Indexed`).

## Baseline (01-baseline.md; ms (buffers), custom)
| status | A | B | C | D | E |
|---|---|---|---|---|---|
| NEW | 0.33 (8) | 9.85 (161) | 9.71 (159) | 1.00 (19) | 0.33 (8) |
| FAILED | 0.04 | 0.04 | 0.04 | 0.04 | 0.05 |
| COMPLETED | 23.9 (794) | 19.6 (642) | 0.03 | 22.8 (783) | 23.4 (794) |

Plan: parallel index-only scan of `idx_outbox_record_status` (6.4 MB thanks to B-tree deduplication).
The `-generic` / `-costs` files for COMPLETED (82 ms A, 67 ms B, ~92k buffers) are a visibility-map artefact, not a
generic-plan effect: `Heap Fetches 993,800`, because they ran after the rolled-back `deleteByStatus`/`deleteByIds`.
Realistic range: 24 ms (all-visible) to ~80 ms (pages recently updated, not yet vacuumed) per 1M COMPLETED rows.
Linear in history; no index can avoid it (H30).

## Hypotheses
- H30 (guard): cost under status-index replacements (H31, H40 replace, drop) and VM states. No DB-level gain possible.
- Affected by H31 variant a and H40 replace (both replace `idx_status` with a composite: expected +10-40% COMPLETED).

## Open questions
Counting COMPLETED every refresh is behavioural (drop/slow down/estimate the COMPLETED gauge; `countByStatuses` SPI).

## Outcome
_pending_
