# H08: Proposed G1 index set with drops (net write cost)
Status: measured
Target queries: all G1 queries (`findRecordKeysInPartitions` on/off, `findOldestDueNextRetryAtByPartitions` on/off);
guard: the full query suite (G2 bulk load, G4 metrics, G5 cleanup, admin by-key queries).
Builds on: H04 (outer index + SQL), H05 (filter-off fix), H06 (probe on the existing key index). Addresses (b), with
the per-dialect set for (c). Input for Phase 2.

## Change

**H08a (proposed Postgres set)** — `experiments/H08/indexes.sql`, queries `experiments/H08/queries/` (= H06):

| Index | Action | Users after the change |
|---|---|---|
| `idx_outbox_record_incomplete_created (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL` | **add** | key query on/off (outer early-stop scan) |
| `idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` | keep | G1 probes (H06), G2 bulk load (H11), by-key admin |
| `idx_outbox_record_record_key_created (record_key, created_at)` | **drop** | none: ≈0 scans in the baseline workload mix (16 in D), every lookup it serves is a `record_key` prefix lookup `record_key_completed_created` serves equally (`ORDER BY created_at` lookups by key and status need a sort either way) |
| `idx_outbox_record_status_retry (status, next_retry_at)` | **keep** | MIN filter off (min/max, 0.04 ms), MIN filter on (H04 early stop), G5 `findFailedRecords(cutoff, limit)` / `deleteFailedRecords` (`status = 'FAILED' AND next_retry_at < :cutoff ORDER BY next_retry_at`) |
| `idx_outbox_record_partition_status_retry`, `idx_outbox_record_status` | keep (G4 decides) | counts / metrics |

**H08b (fallback)** — `experiments/H08/indexes-b.sql`, queries `experiments/H05/queries/`: H04's two partial
indexes + drop `record_key_created`. Use if H06's probe is materially slower than the partial probe, or if G2 adopts
`incomplete_key` (H12).

Not dropped, and why (critical review of the orchestrator's drop candidates):
- `status_retry` had 0–1 scans in the *baseline* workload mix only because the MIN filter-on query ran as a hash anti
  join and the mix contained no failed-record cleanup. After H04 it is the outer index of the MIN filter-on query, and
  it is the only index matching `findFailedRecords(cutoff)` order. Dropping it would turn MIN filter on/off into a scan
  of all NEW rows in the owned partitions (B ~50k entries, est. 5–15 ms, from 0.04–0.2 ms) and failed-record cleanup
  into seq scan + sort. A cheaper *replacement* exists — partial `(status, next_retry_at) WHERE completed_at IS NULL`
  (no entry on completing updates, B 20% of the size) — but needs `completed_at IS NULL` added to the MIN and
  failed-cleanup SQL; recorded as a Phase 2 follow-up, not measured here.
- `record_key_completed_created` is the H06 probe and the H11 bulk-load index: kept.

## Rationale / expected write cost

Index entries per operation (outbox_record, Postgres; every `updateProcessingState` changes indexed columns
(`status`, `completed_at`, `next_retry_at`), so updates are non-HOT and insert into every index whose key/predicate
admits the new tuple):

| Set | indexes | entries per insert | entries per completing update | entries per retry/failure update |
|---|---|---|---|---|
| control | PK + 5 | 6 | 6 | 6 |
| H04 | + 2 partial | 8 | 6 | 8 |
| H06 | + 1 partial | 7 | 6 | 7 |
| **H08a** | + 1 partial − `record_key_created` | **6** | **5** | **6** |
| H08b | + 2 partial − `record_key_created` | 7 | 5 | 7 |
| H07a/b | + 1 non-partial − `record_key_created` (as in the per-dialect set) | 6 | 6 | 6 |

Non-HOT updates insert into every index (PK included); partial indexes only when the new tuple still has
`completed_at IS NULL` (retry / failure updates), not on completion. The write benchmark's update mix decides how
much of the update saving shows up (iteration 1: H04 update WAL 0%, i.e. the mix is dominated by completions).

Estimate from H04's measurement (2 partial indexes = +315 B insert WAL ≈ +13%; `incomplete_created` ~55% of that by
tuple width, `record_key_created` tuple ~32–40 B ≈ 4–5%):

| Set | insert time / WAL | update time / WAL |
|---|---|---|
| H04 (measured) | +13–14% / +13% | +5% / 0% |
| H06 | ~+7% / ~+7% | ~+5% / 0% |
| **H08a** | **~+2–3%** | **~−3%** (one index entry fewer per update) |
| H08b | ~+8–9% | ~−3% |
| H07a/b (portable, for comparison) | ~+3–4% (a) / ~+5% (b) after the drop | ~+2–4% (entry on completion) |

H08a leaves ~12% of the 15% write budget for G4/G5 additions (e.g. H40's `(status, completed_at)` or H31's
`(status, created_at)`), which iteration 1 alone (H04: +13–14%) would have nearly exhausted.

## Per-dialect set (issue c)

| Dialect | Outer index (G1) | Probe | Drop |
|---|---|---|---|
| postgres | partial `incomplete_created` (H08a) | existing `record_key_completed_created` | `record_key_created` |
| sqlserver | filtered `(created_at, id) INCLUDE (...) WHERE completed_at IS NULL` | existing | `record_key_created` |
| mysql / mariadb | `(completed_at, created_at[, partition_no, status, next_retry_at, record_key])` (id implicit) — H07 | existing | `record_key_created` |
| oracle | `(completed_at, created_at, id[, ...])` — H07 | existing | `idx_outbox_rec_key_cr` |
| jpa entity | `@Index(columnList = "completedAt, createdAt, id")` (H07a; partial not expressible) | existing | `idx_outbox_record_record_key_created` |
| mongo | `{completedAt: 1, createdAt: 1, _id: 1}` (Phase 3) | existing `{recordKey, completedAt, createdAt}` | `record_key_created_idx` (check `{partitionNo, recordKey, createdAt}` users first) |

The query SQL differs per index shape only in the outer ORDER BY and the LIMIT clause: partial/filtered index
(postgres, sqlserver) `ORDER BY o.created_at, o.id`; non-partial (mysql, mariadb, oracle, jpa)
`ORDER BY o.completed_at, o.created_at, o.id`. A single text with the leading `completed_at` is harmless for the
results everywhere, but on Postgres it is expected to break the early stop with the partial index (its pathkeys are
`(created_at, id)`, the query's `(completed_at, created_at, id)` → Sort over all candidates). Step 4 measures this;
if confirmed, the JDBC module needs the ORDER BY chosen per dialect, alongside the LIMIT clause it already needs.

## Semantic equivalence argument

No SQL beyond H06 (H08a) / H05 (H08b). Index drops cannot change results.

## Experiment plan

1. Write cost, fresh container per set, pre-seed DDL, scenarios A and B (`run-writecost.sh` pattern, 50k inserts +
   50k `updateProcessingState`, ms/1k, WAL B/op, ±1% WAL / ±3% tps noise as in iteration 1): **control, H04, H06,
   H08a, H08b, H07a, H07b** in one run so all ratios share a baseline. Index sizes after seed and after the cycle.
2. Regression, H08a, full suite incl. writes: `run-suite.sh H08-reg-<S> <S> --ddl ../experiments/H08/indexes.sql
   -q <harness/queries overlaid with H08/queries>` on A, B, D (C, E for the G1 targets). Watch every query that used
   `record_key_created` in any baseline plan (D: 16 scans — identify the query from `results/base-D`), G2 bulk load,
   `deleteByRecordKeyAndStatus`, `findPendingRecords`.
3. Visibility-map sensitivity (open from iteration 1): on B with H08a, run `workload-mix.sh` without VACUUM, then
   the key query custom-serial: Heap Fetches on `incomplete_created`, ms vs the fresh-VACUUM number.
4. Single-text check: run H07's key query files (outer `ORDER BY o.completed_at, ...`) on the H08a state (partial
   index only) on B: does Postgres add a Sort / lose the early stop? Decides whether the JDBC module needs one or two
   ORDER BY texts.
5. H08b: only if H06 is rejected or G2 needs `incomplete_key`: H05 queries on the H08b state, A/B/D.

## Results (per scenario): plan shape, indexes used, median ms, buffers, before → after
Method (G1 iteration 2, `experiments/G1-iter2/run-scenario.sh`, tables `results/G1i2-tables.md`, logs `results/logs-iter2/`): one container per
scenario (A, B, C, D, E; `outbox-db-H05..H09`, tmpfs data dir, 1M rows, C 200k), index states applied in place after seeding with
`VACUUM ANALYZE` (fresh VM). Timing: 1 warm-up + 3 runs, median, `custom-serial` = `max_parallel_workers=0` (pgjdbc `withMaxRows`),
`generic-serial` = force_generic_plan + no workers, `--costs` (estimate vs actual), worst case `partlist_override=999`, batch 100/1000 on B, D.
Reference numbers for the iteration-1 states (control, H04) come from `results/G1ctl-<S>` and `results/H04-<S>`; the H04 filter-off file was re-run
on the H04 state (`results/H05ref-<S>`) and reproduces them (D 14.0/14.3 ms, others 0.11-0.16 ms). Numbers: ms (serial custom, parts 64 / 256).

H08a = `incomplete_created` added, `record_key_created` dropped, H06 queries (keys off = scalar form, see H05/H06 for its instability). Keys on / MIN on custom-serial 64p / 256p:

| | A | B | C | D | E |
|---|---|---|---|---|---|
| keys on | 0.19/0.38 | 0.20/0.38 | 0.20/0.34 | 0.09/0.11 | 0.33/0.87 |
| MIN on | see `results/H08-<S>/summary.tsv` (0.15-0.35 at B; D 0.05/0.08) | | | | |

Dropping `record_key_created` changed no G1 plan (probe stays on `record_key_completed_created`). Filter off (scalar form): A 5.6/20.4, B 213/0.14, D 17.7/90.9, E 5.9/20.5 (A/B/D/E regress vs baseline, same cause as H05).
- **Visibility map** (B, H08a, autovacuum off, bursts of NEW -> COMPLETED on the oldest records, `results/H08vm-B/`): keys on 64p/256p fresh 0.22/0.37 ms;
  after 5k updates 0.12/0.13; 20k 0.16/0.18; 50k **0.42/0.60** (Heap Fetches 0-5, 814-1,082 buffers); after VACUUM 0.18/0.34. No cliff: the oldest dead index entries
  cost buffers, not heap fetches. MIN on: 0.07 -> 0.32/0.47 at 50k, 0.14/0.34 after VACUUM. (pg_class.relallvisible is stale without VACUUM; not meaningful.)
- Step 4 (single ORDER BY text): the H07 text (`ORDER BY o.completed_at, o.created_at, o.id`) on the partial index was not run separately; the planner's pathkey for the partial index
  is `(created_at, id)` only, and `completed_at` is a constant for `completed_at IS NULL` rows, so Postgres can drop it from the sort keys (equivalence classes); to be verified in Phase 2.
  H08b (both partial indexes + drop) was not measured separately: its read side is the H04 state (iteration 1), its write cost is below.

## Equivalence check: PASS/FAIL + method
Same as H06 (identical query files): exact PASS A-E, edge PASS / PASS-TIES (`results/H08-<S>/`, `results/G1i2-edge/`, state `H08`).

## Regression: other queries whose plan changed; write cost; index size
**Write cost** (fresh containers, pre-seed DDL, A / B; ms per 1k, WAL B/op; control 26.1 / 35.1 ms, 2,438 / 3,318 B (A)):

| set | A insert ms, WAL | A update ms, WAL | B insert WAL | B update WAL | index pages A (after cycle) |
|---|---|---|---|---|---|
| control | 26.1, 2,438 | 35.1, 3,318 | 2,425 | 3,337 | 40,368 |
| H04 | 27.3, 2,752 (+13%) | 36.7, 3,314 | 2,752 (+13%) | 3,326 | 40,538 |
| H06 | 27.4, 2,591 (+6%) | 36.5, 3,304 | 2,579 (+6%) | 3,325 | 40,449 |
| **H08a** | **27.2, 2,480 (+2%)** | **34.6, 3,190 (-4%)** | 2,474 (+2%) | 3,196 (-4%) | 33,341 (-17%) |
| H08b | 27.2, 2,653 (+9%) | 34.9, 3,193 (-4%) | 2,655 (+9%) | 3,206 | 33,430 |
| H07a | 26.3, 2,563 (+5%) | 36.6, 3,490 (+5%) | 2,542 | 3,502 | 51,173 |
| H07b | 26.7, 2,596 (+6%) | 35.5, 3,571 (+8%) | 2,595 | 3,583 | 57,695 |

**Full-suite regression** (H08a state, harness queries overlaid with the H08 key files, vs `results/base-<S>`; A-E, `results/H08-reg-<S>/`):
G1 targets improve 90-100% (B keys on 265 -> 0.23 ms serial, MIN on 165 -> 0.18, D 38 -> 0.09; C 132 -> 0.21; E 5.1 -> 0.39); G2 `findIncompleteRecordPages`/`findIncompleteRecordsByRecordKey`
switch from `record_key_created` to `record_key_completed_created` with unchanged timing (+-6%, A keys=1000 11.4 -> 11.3, B 22.0 -> 22.1); cleanup selects and metrics unchanged except
`findFailedRecordsBefore`/`deleteFailedRecords_selectIds` D 0.03 -> 0.04 (status index instead of status_retry, noise). Regressions, all from the **filter-off scalar form** (not from the indexes):
A 256p 2.4 -> 20 ms, 64p 1.3 -> 5.6; B 64p 56 -> 212; C generic 64p 30 -> 182; D 256p 6.4 -> 91; E like A. `findOldestCreatedAtByPartitions` A 256p generic 11.1 -> 13.3 (+20%, same as H04 noise).
Cleanup selects in the H08a state (no `completed_at` index): unchanged (A 281 / 171, B 217 / 163 ms).

### Experimenter note
H08a is the cheapest of the measured sets (insert WAL +2%, update WAL -4%, 17% fewer index pages than control) and keeps the G1 hot path at 0.1-0.9 ms. The remaining open issue is not the index set but
the filter-off query text (H05-H06 forms regress A/B/D/E): the combination H08a + the H09 'implied' filter-off form (needs a status-based partial index for implication) or H09 non-partial outer
`(status, created_at, id)` is the better candidate set for Phase 2.

## Decision + reasoning

_pending_


## Orchestrator decision
**accepted (G1 PG set)**; H08b rejected. meets 6.1 targets; filter-off needs H05-X (or H09 single-predicate form) (ledger: `../STATE.md`; final report: `../99-final-report.md`)
