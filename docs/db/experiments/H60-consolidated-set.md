# H60: Consolidated Postgres index set and query rewrites (Phase 2)
Status: measured
Target queries: the full JDBC suite (all `harness/queries/*.sql`).
Author: orchestrator (Phase 2 analyst step), from the decisions in `../STATE.md`.

## Change

### Index set (Postgres)
| Index | Action | Source |
|---|---|---|
| `idx_outbox_record_incomplete_created (created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL` | add | H08a |
| `idx_outbox_record_record_key_created (record_key, created_at)` | drop | H08a, H10/H16 (unused, prefix duplicate) |
| `idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` | keep (S1) or replace by `(record_key, completed_at, created_at, id)` (S2 = U2b) | H06, H14, H16 |
| `idx_outbox_record_status_retry (status, next_retry_at)` | keep | H08 (MIN queries, findFailedRecords(cutoff)) |
| `idx_outbox_record_status (status)` | keep | H30 |
| `idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)` | keep (removal re-checked here) | H32 |
| `CREATE STATISTICS outbox_record_status_completed_stx (mcv) ON status, completed_at` | add (F1) | H05-X |

DDL files: `H60/indexes-S1.sql` (H08a + stats), `H60/indexes-S2.sql` (H08a + U2b + stats).

### Query rewrites (one query dir `H60/queries/` = copy of `harness/queries/` with these overlaid)
| Method | Rewritten form | Source file |
|---|---|---|
| `findRecordKeysInPartitions` (filter on) | head-of-key + LIMIT, probes on key/completed index | `H08/queries/findRecordKeysInPartitions_filter.sql` |
| `findRecordKeysInPartitions` (filter off) | F1: H04 form + extended statistics | `H04/queries/findRecordKeysInPartitions_nofilter.sql` |
| `findOldestDueNextRetryAtByPartitions` (filter on) | H08 form | `H08/queries/findOldestDueNextRetryAtByPartitions_filter.sql` |
| `findOldestDueNextRetryAtByPartitions` (filter off) | unchanged | harness |
| `findIncompleteRecordPages` | H14 Lon (LATERAL per key, no top ORDER BY) | `G2-iter2/qdirs/Lon/findIncompleteRecordPages.sql` |
| `findOldestCreatedAtByPartitions` | + `AND completed_at IS NULL` | `H32/queries/findOldestCreatedAtByPartitions.sql` |
| `find{Completed,Failed}RecordsBefore`, `delete{Completed,Failed}Records_selectIds` | real SQL LIMIT | `H41/queries/*.sql` |
| all others | unchanged | harness |

## Rationale
Each component was accepted in Phase 1 in isolation. Phase 2 checks the combination: no hot-path query regresses
by more than 10% against its best isolated result, plan stability (custom and generic), write cost of the full
set at most +15% against the baseline, and removal candidates after the changes (index-usage workload mix).

## Experiment plan
1. Build `H60/queries/` and the two DDL files. Equivalence on A–E + edge for every rewritten query (re-use the
   Phase 1 checks; one run per scenario is enough).
2. Full suite (`run-suite.sh`) for S1 and S2 on A, B, C, D, E: custom, serial and generic. Compare per query with
   the baseline (`results/base-*`) and with the isolated Phase 1 numbers in the STATE ledger.
3. Filter-off key query: F1 (with statistics) against the same query without statistics, on B and D. Check
   whether the 204 ms flip in B (seen with `partition_status_retry` present) is gone.
4. Index usage workload mix (5.6) on A and B: list indexes with no or negligible scans → removal candidates.
   Re-check `partition_status_retry` and `status_retry`.
5. Write cost for S1 and S2 on A (WAL/op and ms/1k, ins/upd), total index size, against the baseline.
6. Visibility map: hot-path queries after 50k updates without VACUUM (S1).

## Results (per scenario)
Full per-query matrix (baseline -> S1 -> S2, A/B/D, custom and generic, plan shape and index): `../results/H60-matrix.md`. Drivers: `H60/run-all.sh`,
`run-scenario.sh`, `run-misc.sh` (edge, VM), `run-drops.sh`, `run-recheck.sh`; raw data `results/H60-*`.

Hot path (key filter 64p + oldest-due filter 64p + pages 100 keys + oldest created 64p, custom, key/due serial), ms:

| set | A | B | D | B vs baseline |
|---|---:|---:|---:|---:|
| baseline | 11.6 | 487 | 121 | |
| S1 | 0.98 | 1.20 | 22.9 | -99.8% |
| S2 | 0.81 | 1.04 | 9.1 | -99.8% |

Hot path scales with the incomplete rows in owned partitions, not with the table: B has 33x the incomplete rows of A but S1 goes 0.98 -> 1.20 ms (baseline 11.6 -> 487).
D is dominated by `findIncompleteRecordPages` at 100 keys (long per-key histories: 22.7 ms in S1, 8.8 in S2); without that query D is 0.3 ms.
Selected values, S1 / S2 (baseline) A/B/D:
- key filter 64p: 0.22/0.21/0.087 | 0.21/0.22/0.10 (5.0/265/38); 256p: 0.34/0.38/0.11 | 0.40/0.34/0.11 (19/367/141)
- key filter off 64p: 0.15/0.13/0.09 | 0.13/0.14/0.09 (1.3/56/4.5); 256p 0.21/0.13/0.10 | 0.14/0.14/0.10 (2.4/76/6.4)
- oldest due filter 64p: 0.18/0.15/0.06 | 0.16/0.16/0.07 (4.7/165/39)
- oldest created 64p: 0.067/0.065/0.033 (1.0/50/4.6); generic <= 0.12 ms
- pages 10/100/1000 keys: S1 B 0.15/0.77/8.1, D 2.4/22.7/22.2; S2 B 0.11/0.59/6.4, D 0.85/8.8/9.2 (baseline B 0.18/6.6/22, D 3.9/40/40; A 0.31/0.84/4.0)
- cleanup selects (real LIMIT): 72-113 ms in all scenarios (baseline serial 157-273 ms; Phase 1 H41 range 77-132); admin full scans unchanged.
- C and E (S1): key filter 64p 0.22 / 0.39 ms (baseline 132 / 5.1), oldest created 0.058 / 0.11, pages 1000 keys 5.1 / 4.8 (12.1 / 4.1).
- No Seq Scan in any hot-path plan, custom or generic, any scenario. Custom plans: both sets. Generic (force_generic_plan, serial for key queries): hot queries 0.1-1.1 ms in A/B/D, no plan flip. Details in matrix.

Write cost (A, WAL B/op, baseline 2445 insert / 3324 update): S1 2490 (+1.8%) / 3200 (-3.7%); S2 2568 (+5.0%) / 3312 (-0.4%). ms/1k: 26.0/35.0 -> S1 26.2/35.2, S2 26.1/34.3.
Target <= +15%: met by both. Index pages after the run (8 KiB): baseline 43374, S1 36564 (-16%), S2 45326 (+4.5%).

F1 (filter-off key query with vs without extended statistics, `results/H60-S1-{B,D}`, `-nostats`, `-origtext`), custom serial 64p/256p, ms:

| | B | D |
|---|---|---|
| F1 text + stats (S1) | 0.17/0.13 | 0.09/0.10 |
| F1 text, no stats | 0.13/0.14 | 0.09/16.1 (191k rows removed, flip) |
| baseline text on S1 indexes + stats | 53/71 | 5.0/6.9 |
| H08-B (no stats, baseline text, psr present), Phase 1 | 213/0.14 | |

The H05-X statistics remove the D 256p misestimate flip (16 -> 0.10 ms); the baseline text remains slow regardless (53/71 B), so the text rewrite is required. The 204-213 ms
B flip with `partition_status_retry` is not present in S1 with either text (53/71 baseline text, 0.13/0.14 F1). Generic plan for F1 without parallelism is 0.5-0.6 ms.
Without `withMaxRows` the forced-generic F1 plan is a parallel Gather Merge that does not stop early (B 6.5 ms, D 25/145 ms): see Regression F-2.

Visibility map (S1, B, autovacuum off, 50k NEW -> COMPLETED updates, serial; `results/H60vm-B`): pg_class.relallvisible is only refreshed by VACUUM/ANALYZE and stayed 89393/89393 in the snapshots, so it does not show the dirty pages. Hot path after 5k/20k/50k updates, ms: key filter 64p 0.12/0.22/0.52 (fresh 0.22, vacuumed 0.19), key off 0.13/0.18/0.33,
oldest due 0.07/0.10/0.28, oldest created 0.07/0.11/0.22, pages 1000 keys 6.9/7.1/6.5. No cliff; growth is linear in dirty pages and everything stays below 0.6 ms.

## Equivalence check
PASS. `results/H60-eq-S1-{A,B,C,D,E}.txt`, `H60-eq-S2-{A,B,D}.txt`: 33/33 checks per run, 0 FAIL (key filter/off 64p/256p full + head 10 + generic, oldest-due, pages 10/100/1000 custom+generic
(exact ordered md5), oldest created 64p/256p, four cleanup selects at cutoff 0.1/0.9 and limit 1/1000; invariant `NEW => completed_at IS NULL` holds: 0 violations).
Edge seed (`results/H60-eq-edge.txt`, key_ties 0/1, custom + generic, S1): 72 PASS, 14 PASS-TIES (order differs only among keys with equal MIN(created_at); the original tie order is itself plan dependent), 0 FAIL.

## Regression
Hot path vs best isolated Phase 1 number (table in matrix; first pass, then 7-run recheck on D):
- Flags >10% and >0.05 ms: key off 256p A S1 0.12 -> 0.21 (S1 repeat in the drop run: 0.14-0.15, noise); key filter 64p D S2 0.089 -> 0.165 (recheck, 7 runs: 0.087-0.098, noise); due filter 256p A S2 0.30 -> 0.36 (noise level, S1 0.31).
  Oldest created 64p A/B 0.049 -> 0.067 (+37%, 0.02 ms) and key off 64p B 0.116 -> 0.134 are below measurement resolution. No regression above 0.1 ms in absolute terms.
- vs baseline: all hot-path queries -89% to -100%, except `findIncompleteRecordPages` 1000 keys on A: 4.04 -> 4.71 (S1, +16%; S2 4.06) and 10 keys on B 0.18 -> 0.15 (flat). The A 1000-key delta is the known trade-off of the Lon form (isolated 4.84).
- S2 vs S1: pages 100/1000 keys D 22.7/22.2 -> 8.8/9.2, B 0.77/8.1 -> 0.59/6.4, A 0.52/4.7 -> 0.38/4.1; everything else equal within noise. Cost: +63 MB index (131 vs 68 MB), +3.2 percentage points insert WAL.
- Other queries (counts, admin, writes): unchanged within noise in A/B/D (see matrix); countByStatus(COMPLETED) 20-24 ms and `findPendingRecords` B 213 ms are baseline behaviour.
- F-2 (open risk): the rewritten key queries have a real LIMIT. If `withMaxRows` is dropped, a generic plan may use parallel workers (Gather Merge over `incomplete_created`, whole anti join evaluated before Limit): B 6.5 ms, D 25/145 ms (64p/256p), vs 0.44-0.62 ms serial. Custom plans (what pgjdbc uses; stayed custom in every probe) are fine either way (D 0.09/0.10 ms). Recommendation: keep `withMaxRows(batchSize)` in addition to the SQL LIMIT.
- No hot-path seq scans; no plan flip custom vs generic in A/B/D beyond the F-2 case.
- 6.1 targets: scaling with incomplete rows met (B 1.2 ms vs A 1.0 ms, not table size); B hot path -99.8% (target -50%); write cost +1.8% / -3.7% (S1), +5.0% / -0.4% (S2) vs <= +15%.

Removal candidates (workload mix + drop tests, details in matrix):
1. `idx_outbox_record_record_key_created`: already dropped in the set (0 scans in baseline mix, prefix of `record_key_completed_created`).
2. No further index is unused in the mix after the changes. Every remaining index has scans: `incomplete_created` 110, `record_key_completed_created` 6080-8962, `status` 31-41, `status_retry` 10, `partition_status_retry` 640 (one planner tie in S1-B), pkey.
3. Optional trade-offs, not free: `idx_outbox_record_status` (6 MB): dropping saves 2.7% WAL, but `countByStatus(COMPLETED)` goes 24 -> 76 ms (A), 20 -> 33 (B), generic unchanged; the other status queries get faster. `partition_status_retry` (68 MB): dropping saves 6% WAL but `countRecordsByPartition(s)` B 4/17 -> 47/49 ms and generic 5/17 -> 79/211 ms (deprecated metrics only; useful removal if those are disabled). `status_retry` must stay: dropping makes `findOldestDueNextRetryAtByPartitions` 36-56 ms in B (filter) and 18.7 ms (off, 256p).

## Decision


## Orchestrator decision
**S1 accepted (recommended); U2b optional add-on**. keep withMaxRows next to LIMIT (forced-generic parallel plans don't stop early: D 25/145 ms); no regressions; status & psr optional removals rejected (count penalties) (ledger: `../STATE.md`; final report: `../99-final-report.md`)
