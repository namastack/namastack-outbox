# Current `main` vs `feature/db-jdbc-optimization` (PostgreSQL)

Driver `harness/bin/cmp-pg.sh`, 2 h 41 min, 0 failures. `cmp-main` = `f4446c8` (includes GH-551), `cmp-jdbc-opt` = `01165d15`, each with its own shipped DDL and SQL. Two passes, reversed order, container restart. Results `results/<variant>/postgres/<label>-v<1|2>-CMP/`. Cells: median ms, pass 1 / pass 2, or one range over both passes. Thresholds from `STATE.md`. JPA not measured (user decision). Pass 1 write cells after a gen are colder for `cmp-main`, which ran first; pass 2 reverses this.

Catalog check: the branch's PostgreSQL SQL equals the `D3` SQL except for whitespace in `insert` and `completed_at IS NULL` on `find_incomplete_by_key`.

Equivalence `results/equiv/postgres/{S2,S5,S7}/cmp-jdbc-opt-vs-cmp-main.md`: 26 PASS each, no WARN or FAIL. Plan check: no queue index in any branch `pages` plan.

## Verdict

**The branch wins against current `main`, but for different reasons than against the old baseline.** GH-551 already fixed `keys_strict`, the default key query (`stop-on-first-failure: true`): there is no difference there except S5. The gains are S10 throughput, `pages` under concurrency, `upd_complete` WAL with a backlog, index size, `oldest_due_strict`, and non-strict key selection. **The deprecated metrics module gets far slower; the user accepted this and kept the branch as it is (2026-11-04).**

## Priority 1

| Cell | `main` | branch | Result |
|---|---|---|---|
| S10 on S2, mean proc/s (range, 4 runs) | 1030 (979–1106) | 1268 (1191–1421) | +23 %, ranges do not overlap |
| S10 on S4, mean proc/s (range, 4 runs) | 912 (837–968) | 1117 (1010–1205) | +22 %, ranges do not overlap |
| S10 on S2 `pages` p50 / p95 | 6.0–8.5 / 19–23 | 0.70–1.27 / 1.5–1.6 | large win; 3031–4550 slow `pages` plans per run on `main`, 0 on the branch |
| S10 on S4 `pages` p50 / p95 | 1.1–1.7 / 2.3–3.3 | 1.2–1.4 / 1.7–1.9 | p50 no difference, p95 better in all 4 pairs |
| S8-S2 `keys_strict` | 0.95 / 0.95 | 0.86 / 0.93 | no difference |
| S8-S4 `keys_strict` | 1.02 / 0.95 | 0.88 / 0.92 | no difference |
| S8-S5 `keys_strict` | 196 / 196 | 163 / 165 | −16 %, no difference |
| S8-S6, S8-S7 `keys_strict` | 0.93–1.09 | 0.88–0.94 | no difference |
| S5 `keys_strict` | 10.1 / 9.9 | 1.28 / 1.31 | −87 % |
| S8-S4 `keys_nonstrict` | 294 / 282 | 0.90 / 0.90 | win, non-default mode |
| S4 `keys_nonstrict` | 687 / 689 | 1.11 / 1.12 | win, non-default mode |
| S8-S5 `keys_nonstrict` | 22.3 / 21.7 | 1.05 / 1.01 | win, non-default mode |
| S8-S6 `keys_nonstrict` | 19.3 / 18.6 | 1.00 / 0.92 | win, non-default mode |
| S8-S5 `pages` | 101 / 103 | 60 / 62 | −40 % |
| S5 `pages` | 108 / 116 | 71 / 72 | −36 % |
| Other S8 `pages` | 0.66–0.93 | 0.68–1.28 | no difference |
| Single-statement writes, all labels | | | no consistent difference |

Write cost (`S2-wc`, `S4-wc`, committed, 1 thread):

| Measure | `main` | branch | Result |
|---|---|---|---|
| S4 WAL `upd_complete`, bytes | 21309 / 23038 | 8999 / 8997 | **−59 %** |
| S2 WAL `upd_complete`, bytes | 7815 / 7833 | 6294 / 6293 | −20 %, below threshold |
| S4 WAL insert, bytes | 7388 / 7396 | 7244 / 7247 | no difference |
| S2 WAL insert, bytes | 6663 / 6747 | 6282 / 6277 | no difference |
| S4 `upd_complete` p50 | 2.60 / 2.81 | 2.01 / 2.09 | −24 %, below threshold |
| S2 `upd_complete` p50 | 1.93 / 1.94 | 1.98 / 2.13 | no difference |
| Insert p50 | 1.83–2.07 | 1.50–2.08 | no difference |

Size (index count 6 vs 5, table unchanged):

| Measure | `main` | branch | Result |
|---|---|---|---|
| S2 indexes | 430 MB | 77 MB | −82 % |
| S4 indexes | 836 MB | 433 MB | −48 % |
| S5 indexes | 209 MB | 94 MB | −55 % |
| S9 indexes after churn | 846 / 776 MB | 321 / 285 MB | −63 % |
| S9 churn duration | 314 / 324 s | 266 / 282 s | −14 %, below threshold |

## Priority 2

| Cell | `main` | branch | Result |
|---|---|---|---|
| S8-S4 `oldest_due_strict` | 532 / 525 | 0.75 / 0.83 | win |
| S8-S5 `oldest_due_strict` | 498 / 494 | 0.72 / 0.71 | win |
| S8-S6 `oldest_due_strict` | 191 / 18.5 | 0.65 / 0.78 | win; `main` unstable |
| S8-S7 `oldest_due_strict` | 12.6 / 11.2 | 0.60 / 0.67 | win |
| S8-S2 `oldest_due_strict` | 1.58 / 1.60 | 0.79 / 0.64 | win |
| S5 `oldest_due_strict` | 2660 / 2630 | 0.92 / 0.90 | win |
| S8-S4 `count_partitions` | 16.4 / 16.1 | 10.1 / 10.2 | −38 % |
| S8-S6 `count_partitions` | 5.6 / 5.6 | 2.5 / 2.5 | −55 % |
| S8-S5 `count_partitions` | 2.72 / 2.68 | 1.88 / 1.85 | −31 % |
| **S8-S7 `count_partitions`** | 0.71 / 0.75 | 1.35 / 1.27 | **regression, +0.6 ms** |
| **`oldest_created`, S8-S2 to S8-S7** | 0.19–0.29 | 0.37–0.48 | **regression, about +0.2 ms** |
| `oldest_due_nonstrict` | 0.19–0.31 | 0.22–0.31 | no difference |

Status counts per collector refresh, computed by adding cells. `main` runs three `countByStatus` (`OutboxMetricsCollector.kt:148`); the branch runs one `count_statuses` for `NEW` and `FAILED`, so it has no `COMPLETED` gauge by default.

| Label | `main`, 3 queries | branch, default | branch, `COMPLETED` enabled |
|---|---|---|---|
| S8-S2 | 42 | 1.4 | **147–175** |
| S8-S4 | 82 | 40 | **254–321** |
| S8-S5 | 33 | 13 | 74–76 |
| S8-S6 | 40 | 13 | **169–170** |
| S8-S7 | 39 | 28 | **177–187** |

## Deprecated metrics module (accepted, not fixed)

The module is not a target in `STATE.md`, but it auto-configures when on the classpath, and the size of this change was not known before.

| Cell | `main` | branch |
|---|---|---|
| S2 `count_partition_single` | 0.24–0.30 | **106–109** |
| S4 `count_partition_single` | 0.55–0.56 | **200** |
| S5 `count_partition_single` | 0.35–0.50 | 49–50 |
| S2 `count_new` | 0.42–0.48 | 96 |
| S4 `count_new` | 40 | 188–192 |
| S2 `count_failed` | 1.04–1.07 | 98 |

Cause: parallel seq scan (`cmp-jdbc-opt/postgres/S8-S2-v1-CMP/plans/count_partition_single.txt`). `PostgresJdbcOutboxRecordQueries` adds `completed_at IS NULL` to `countByPartitionsStatus` and `countByStatuses`, but not to `countByPartitionStatus` and `countByStatus`, so those two cannot use the partial indexes.

`OutboxPartitionMetricsProvider.getProcessingStats` runs one such query per owned partition, and each gauge calls it. At the ~340 queries per scrape noted in `STATE.md`, that is about 37 s on S2 and 68 s on S4, against about 0.1–0.2 s on `main`. This fan-out is calculated, not measured.

Possible fix, not tested and not applied by the user's decision: the same status-dependent overrides for `countByPartitionStatus` and `countByStatus`.

## Priority 3 (accepted by the decision rule)

| Cell | `main` | branch |
|---|---|---|
| S2 `find_failed_before` | 0.29 / 0.23 | 173 / 173 |
| S4 `find_failed_before` | 0.29 / 0.34 | 326 / 324 |
| S2 `delete_by_status_failed` | 11.1 / 11.4 | 203 / 198 |
| S4 `delete_by_key_status` | 0.14 / 0.12 | 278 / 282 |
| S5 `delete_by_key_status` | 14.6 / 14.3 | 87 / 86 |
| S2 `find_completed_before` | 1306 / 2514 | 255 / 250 |
| S4 `find_completed_before` | 2051 / 1877 | 400 / 396 |
| S5 `find_incomplete_by_key` | 18.3 / 17.9 | 7.0 / 7.1 |

## Limits

- PostgreSQL only. Other dialects and JPA are unmeasured against this revision.
- S10 uses the `keys_strict` loop. Its two runs per pass are the only repeated throughput data, and the +22 % and +23 % are below the single-query 30 % HOT threshold, so the claim rests on non-overlapping ranges over 8 runs.
- Single-statement write cells are rolled back and do not include commit cost; the write cost tables do.
- Equivalence covers S2, S5, S7 on the shared snapshot, not S4 or S6.
- Results from `313eb58` are not comparable: `main` here has the GH-551 index and SQL row limit.
