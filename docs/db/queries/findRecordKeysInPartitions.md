# findRecordKeysInPartitions

Group G1 (with `findOldestDueNextRetryAtByPartitions`, same anti-join shape). Class: **hot path**.

## Callers and frequency

- `OutboxProcessingScheduler.loadRecordKeys` (`namastack-outbox-core`), once per poll (fixed or adaptive
  `OutboxPollingTrigger`), inside `partitionCoordinator.withStableAssignments`, with the instance's owned partitions,
  `status = NEW`, `batchSize = settings.polling.batchSize` (default 10) and
  `ignoreRecordKeysWithPreviousFailure = settings.processing.stopOnFirstFailure` (default **true** -> filter variant).
- Returned keys go to `findIncompleteRecordPages` (chunks of <= 1000 keys).
- JDBC applies `batchSize` with `JdbcClient.withMaxRows` (not in the SQL; invisible to the planner and disables
  parallel workers, `01-baseline.md`). JPA uses `setMaxResults` (Hibernate renders a real limit). Mongo uses an
  aggregation pipeline (`buildStrictFifoAggregation` / `buildStandardAggregation`) with `$limit`.

## Contract (SPI KDoc + code)

- Filter on: only keys whose due record has no earlier incomplete record (`completed_at IS NULL`: NEW or FAILED) in
  `(created_at, id)` order. FAILED heads and not-due (`next_retry_at > now`) heads block later records of the key.
- Filter off: every key with a due record of `status` in the partitions.
- Keys ordered by `MIN(created_at)` of the qualifying records. Order between keys with equal `MIN(created_at)` is not
  specified (plan-dependent today).
- Lifecycle invariant used by rewrites: `completed_at` is set only together with `COMPLETED`
  (`OutboxRecord.markCompleted`, restored together in `OutboxRecordProcessor.completeRecord`), so
  `status IN (NEW, FAILED)` <=> `completed_at IS NULL`.

## Current SQL (`JdbcOutboxRecordRepository`)

```sql
-- recordKeysQueryWithPreviousFailureFilterTemplate (filter on)
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC

-- recordKeysQueryWithoutPreviousFailureFilterTemplate (filter off)
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partitions)
  AND o.status = :status
  AND o.next_retry_at <= :now
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC
```

Harness: `harness/queries/findRecordKeysInPartitions_{filter,nofilter}.sql`.

## Baseline (Postgres 18, `01-baseline.md`)

Serial = as executed by pgjdbc (`withMaxRows`). ms (shared buffer hits).

| Variant | parts | A | B | C | D | E |
|---|---|---|---|---|---|---|
| filter on | 64 | 5.0 (7,374) | **265 (106,374)** | 132 (33,368) | 38 (7,273) | 5.1 (7,287) |
| filter on | 256 | 19.2 (27,864) | **367 (106,363)** | 208 (33,368) | 141 (7,273) | 17.8 (26,567) |
| filter off | 64 | 1.3 (510) | 56 (16,832) | 30 (16,434) | 4.5 (1,858) | 1.4 (509) |
| filter off | 256 | 2.4 (510) | 76 (16,832) | 77 (16,684) | 6.4 (1,858) | 2.5 (509) |

- Plans: outer = `idx_status` scan of all NEW rows table-wide, `partition_no = ANY` as filter (never an index
  condition at 64p). Filter on: A/E NL anti join probing `record_key_completed_created (record_key, completed_at IS NULL)`;
  B parallel hash anti join with a parallel seq scan of the whole heap on the inner side (hash spills, 4 batches);
  C seq scans both sides; D hash anti join over a bitmap scan of all incomplete rows, 358k (64p) / 1.9M (256p) rows
  removed by the join filter (quadratic per key, the OR tie-breaker is not sargable).
- B at 64p returns 6,115 keys; the application keeps 10.
- Generic plans much worse (B filter/256 generic-serial 872 ms); `plan_cache_mode=auto` kept custom plans.
- Raw plans: `results/base-<S>/findRecordKeysInPartitions_{filter,nofilter}-parts{64,256}[-serial|-generic|-generic-serial|-costs].txt`.

## Hypotheses

| H | Change | Expected (B serial, filter on, 64p) | Status |
|---|---|---|---|
| [H01](../experiments/H01-sargable-predecessor-bound.md) | add `older.created_at <= o.created_at` (query only, existing indexes) | D 38 -> ~5 ms; B uncertain (265 -> 100-265) | proposed |
| [H02](../experiments/H02-partial-incomplete-key-index.md) | H01 + partial `(record_key, created_at, id) WHERE completed_at IS NULL` | B ~60-90 ms, D ~3-5 ms | proposed |
| [H03](../experiments/H03-covering-partition-incomplete-index.md) | H02 + covering partial `(partition_no, status, next_retry_at) INCLUDE (record_key, created_at, id) WHERE completed_at IS NULL`, outer `completed_at IS NULL` | B ~15-30 ms; filter off 56 -> ~10-15 ms | proposed |
| [H04](../experiments/H04-head-of-key-early-stop-limit.md) | head-of-key form, `ORDER BY created_at, id LIMIT :batchSize`, partial `(created_at, id) INCLUDE (...) WHERE completed_at IS NULL` + H02 | < 1 ms all scenarios; worst case (no due work owned) ~15-30 ms | proposed |

Considered, not proposed: `DISTINCT ON` / `ROW_NUMBER()` "first incomplete per key" (no early stop, Postgres-only for
`DISTINCT ON`); a real `LIMIT` on the current GROUP BY query (aggregate + sort still consume all input); partial
indexes with `WHERE status = 'NEW'` (unusable in generic plans because `status` is a bind parameter).

## Iteration 2 (proposed)

Iteration 1 outcome (`STATE.md`): H01 rejected, H03 rejected, H02 kept as component, H04 refine. H04 measured: keys on
B 266/428 → 0.20/0.36 ms, D 38 → 0.09 ms, generic plans fine, worst case (no due head in owned partitions) 15 ms on B.
Open issues: (a) filter off in D regresses 4.7/5.9 → 14.1/13.6 ms (inner estimate 343 vs 19k rows: independence of
`status = 'NEW'` and `completed_at IS NULL` → Materialize + 190k join-filter rows); (b) two partial indexes = +13–14%
insert; (c) no partial indexes on MySQL/MariaDB/Oracle.

| H | Change | Targets | Expected |
|---|---|---|---|
| [H05](../experiments/H05-forced-head-probe-filter-off.md) | filter off as correlated scalar head subquery `o.id = (SELECT e.id ... ORDER BY created_at, id LIMIT 1)`; variants: `NOT EXISTS ... OFFSET 0` fence (PG-only), `CREATE STATISTICS (mcv) ON status, completed_at` (diagnostic) | (a) | D filter off 14 → ~0.1–0.2 ms; others = H04 |
| [H06](../experiments/H06-probe-on-existing-key-completed-index.md) | drop the partial key index; probes on existing `record_key_completed_created` (`completed_at IS NULL` index cond; filter-off subquery ordered `completed_at, created_at, id`) | (b), (c) probe | insert +13% → ~+7%; latency ≈ H04 (+1 heap visit per probe) |
| [H07](../experiments/H07-nonpartial-completed-created-outer-index.md) | non-partial `(completed_at, created_at, id)` [INCLUDE ...] as outer index, outer `ORDER BY o.completed_at, o.created_at, o.id` | (c), PG proxy for MySQL/MariaDB/Oracle; possible G5 reuse | B ≈ 0.3–0.5 ms; worst case 40–80 ms (lean) / ~15–20 ms (covering); more write cost than partial on PG |
| [H08](../experiments/H08-g1-index-set-and-drops.md) | G1 set: + `incomplete_created`, − `record_key_created`, keep `status_retry` (MIN + failed cleanup use it); per-dialect set | (b) | net insert ~+2–3%, update ~−3% |

Order of measurement: H05 → H06 → H08a (same container, in place) → H07a/b; one write-cost run for control, H04, H06,
H08a, H08b, H07a, H07b.

## Outcome
_pending (Phase 1 iteration 1)_
