# Cross-dialect: MongoDB (mongo:9.0.4, the version used in the module tests)

Scale: 200k documents (10k keys x 20), data on tmpfs, 64 of 256 partitions owned, batch 10. A = 1200 NEW (0.6%), B = 40000 NEW (20%);
in both 40 FAILED heads (blocking) and 20 keys with `nextRetryAt` in the future. Documents as Spring Data writes them (null `completedAt` is absent).
Scripts and raw output: `phase3/mongo/` (`seed.js`, `idx.js`, `bench.js`, `eq.js`, `write.js`, `run1.out`, `run2.out`, `eq.out`, `write.out`, `pf-test.out`).
Counters are serverStatus `queryExecutor.scanned/scannedObjects` deltas (explain `totalDocsExamined` is inflated for `$lookup`), time = median of 3 warm runs.

## Current queries (MongoOutboxRecordRepository) and indexes
- Key selection with the filter on (`findRecordKeysInPartitions`) and oldest-due with the filter on: aggregation `$match {partitionNo $in, completedAt null}` -> `$sort {recordKey, createdAt, _id}` -> `$group first($$ROOT)` -> `$match oldestDoc.status/nextRetryAt` -> `$sort` -> `$limit`. It reads every incomplete document of the owned partitions and cannot stop early.
  Because `completedAt` is not covered by `fifo_pipeline_idx {partitionNo, recordKey, createdAt}`, it reads **all** documents of the owned partitions (49,940 of 200k) and filters after the fetch.
- Key selection with the filter off, oldest created (`status`), counts, cleanup (`status`+`completedAt`/`nextRetryAt` sort+limit, ids then `deleteByIds`), bulk load (`recordKey $in`, `status NEW`, sort `recordKey, createdAt, _id`).
- `@CompoundIndexes`: `record_key_created_idx`, `partition_status_retry_idx`, `status_retry_idx`, `record_key_completed_created_idx {recordKey, completedAt, createdAt}`, `fifo_pipeline_idx`, plus `@Indexed status`.

## Partial index facts (tried on 9.0.4, `pf-test.out`)
Allowed in `partialFilterExpression`: `{completedAt: null}` (`$eq: null`; matches a missing field), `{completedAt: {$type: 'null'}}`, `{status: {$in: [...]}}`, `{status: 'NEW'}`, `$or`, range operators.
Rejected: `{completedAt: {$exists: false}}`, `{status: {$ne: ...}}`, `{status: {$nin: ...}}`. A query uses the partial index only if its filter contains `completedAt: null` literally (same rule as Postgres). Spring's `@CompoundIndex(partialFilter = "{completedAt: null}")` carries it.

## Proposed change
Indexes (partial, written with `partialFilter = "{completedAt: null}"`):
- P1 `incomplete_created_idx {createdAt:1, _id:1, partitionNo:1, status:1, nextRetryAt:1, recordKey:1}`: outer ordered scan for the head-of-key pipeline, filters on index keys (2.2 MB in B).
- P2 `incomplete_key_created_idx {recordKey:1, createdAt:1, _id:1}`: per-key probe, bulk load, and it makes the current strict pipeline read only incomplete documents (1.3 MB in B).
- Drop `record_key_completed_created_idx` (5.5 MB) and `fifo_pipeline_idx` (2.3 MB). No query regressed without them (`run2.out`, M1+M4-drop). Keep `record_key_created_idx` (used by `deleteByRecordKeyAndStatus` and the unfiltered scans), `partition_status_retry_idx`, `status_retry_idx`, `status`.
  Net index size B: -4.2 MB (-24% of the non-_id total). Write cost neutral: 30k inserts 531 -> 537 ms, 30k updates to COMPLETED 9.06-9.39 s -> 9.23-9.33 s.

Query (key selection with filter on, and oldest-due with filter on): head-of-key pipeline
`$match {partitionNo $in, completedAt null, status, nextRetryAt <= now}` -> `$sort {createdAt, _id}` -> `$lookup {from same collection, localField recordKey, foreignField recordKey, pipeline [$match completedAt null, $sort createdAt+_id, $limit 1, $project _id]}` -> `$match {$expr: $_id == first(head._id)}` -> `$limit batch`.
Oldest-due: same, but `$sort {nextRetryAt, _id}` and `$limit 1` (the first head in retry order is the minimum). Oldest-created: add `completedAt: null` to the `$match`, `$sort createdAt`, `$limit 1`. Pages: add `completedAt: null` to the filter.

## Results (ms median / docs examined), before -> after
| query | A | B |
|---|---|---|
| keys, filter on (unchanged pipeline + P2 only) | 58 / 49,940 -> 3 / 1,240 | 76 / 49,940 -> 45 / 40,040 |
| keys, filter on (head-of-key + P1 + P2) | 58 / 49,940 -> 4 / 285 | 76 / 49,940 -> 4 / 101 (586 keys) |
| keys, head-of-key without P1 (P2 only) | 5 / 335 | 28 / 10,077 (blocking sort) |
| oldest due, filter on (head-of-key, sorted by nextRetryAt) | 56 / 49,940 -> 3 / 308 | 73 / 49,940 -> 28 / 10,005 (no early stop; uses `partition_status_retry`) |
| oldest created (`completedAt: null` + P1) | 2 / 297 -> 1 / 1 | 12 / 9,984 -> 1 / 1 |
| pages 100 keys (`completedAt: null` + P2) | 3 / 1,200 -> 3 / 180 | 10 / 2,000 -> 11 / 806 (keys 2,100 -> 906) |
| pages 1000 keys | 6 / 1,200 -> 5 / 180 | 68 / 20,000 -> 38 / 5,726 |
| keys, filter off | unchanged (A 2, B 14: `status` + group is already cheap); head-of-key variant not better (28-36 ms) | |
A naive head-of-key oldest-due (min over all ready heads, 10k probes in B) regresses to 1.5 s; the sorted-first form above is the one to use.
Equivalence (`eq.out`): ready key list identical and identically ordered for limits 1, 10, 100, 1000 and the full list (A 90 keys, B 1239 keys); oldest-due value identical (A 2026-09-30T14:30:02.05Z, B 13:48:22.05Z). Ties on equal `createdAt` are broken by `_id` in both forms.

## Recommendation
1. Cheapest, no query change: add P2, drop `fifo_pipeline_idx` and `record_key_completed_created_idx` (strict pipeline 58 -> 3 ms in A, 76 -> 45 ms in B; scales with incomplete documents instead of partition size).
2. Plus head-of-key pipeline + P1 for the key query (B 76 -> 4 ms, docs 49,940 -> 101) and `completedAt: null` in oldest-created and pages; oldest-due uses the sorted-first form (B 73 -> 28 ms, remaining cost is the blocking sort over `partition_status_retry`; a retry-ordered partial index was not tried).
3. Keep `withMaxRows`-style limits in Kotlin (`$limit` is in the pipeline already). Existing deployments need the two new indexes created; `@CompoundIndex` does not drop removed ones.
