# Why the PostgreSQL outbox_record indexes and queries look the way they do
> Agents of the MySQL study: the `docs/db-sql/...` paths cited below are evidence pointers for the user. Do not open them (isolation rule).
>
> This file was written for the MariaDB study and is reused unchanged for MySQL. Read "MariaDB" as "the target engine (InnoDB)". The remarks marked
> general knowledge about MariaDB do not necessarily hold for MySQL 8.4/9.x (for example MySQL has `LATERAL` derived tables since 8.0.14, converts
> `NOT EXISTS` into an antijoin since 8.0.17, has hash joins, skip-scan range access, invisible and functional indexes, and no `SET STATEMENT`). Verify
> every engine claim for MySQL. The file contains no results for MariaDB or MySQL.

Purpose: hypotheses for an independent MariaDB experiment. This file explains mechanisms and cites PostgreSQL evidence only. It contains no results for any other database. The MariaDB experiment must measure for itself whether each idea carries over.

Conventions
- Paths are relative to `docs/db-sql/` and cite the PostgreSQL experiment. "Final" means branch `origin/feature/db-jdbc-optimization` (`PostgresJdbcOutboxRecordQueries.kt`, `DefaultJdbcOutboxRecordQueries.kt`, `schema/postgres/outbox-tables.sql`) with the `P-KK2` `pages` form. The ADR is `docs/adr/0005-query-priorities-and-jdbc-dialect-optimization.md` on that branch.
- "General knowledge" marks statements about database engines that were not measured in the experiment.
- Evidence labels: S2 = 1M rows, 98.6 % COMPLETED, 1 000 NEW due; S4 = 1M NEW backlog plus 1M COMPLETED; S5 = 100 hot keys x 2 000 NEW; S6 = 195k future retries; S7 = 390k FAILED; S8-x = partial ownership (64 of 256 partitions); S9 = churn; S10 = 4-poller concurrency loop (`02-scenarios.md`).
- Baseline caveat: early rounds compared against an older `main` (`313eb58`) whose strict key query still used GROUP BY. Current `main` (`f4446c8`, with GH-551) already has the strict key query without GROUP BY, so the final comparison is `experiments/CMP-main-vs-jdbc-opt-pg.md`.

---

## 0. Priorities and decision rule that shaped every choice

Source: `STATE.md` ("User decisions", 2026-11-01) and the ADR.
- P1 = scheduling and execution: `keys_strict`, `keys_nonstrict`, `pages`, and the writes of the processing loop (`insert`, `upd_complete`/`upd_retry`/`upd_fail`, `delete_by_id`), plus S10 throughput.
- P2 = the new metrics collector: `count_statuses` (NEW, FAILED), `count_partitions`, `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`.
- P3 = retention/admin: may be slow, only needs correct SQL.
- Rule: an index is justified only by P1/P2, and only if its read benefit outweighs its write cost (insert and update latency, WAL per write, index size, bloat). Dropping indexes to speed up normal processing is an explicit goal. Every DDL change must report write cost.
- Partial partition ownership (an instance owns about 1/4 or 1/16 of the partitions) is the default deployment. Verdicts rank S8-* above full ownership (`STATE.md`, 2026-11-02).
- Invariant that makes everything work: `completed_at IS NULL` holds exactly for `NEW` and `FAILED` (`01-usage-map.md` section 2.1). The hot path only reads incomplete rows, while retained COMPLETED history can be 98 % of the table.

---

## 1. Final PostgreSQL design at a glance

Final DDL (`schema/postgres/outbox-tables.sql` on the branch). Everything except the primary key on `id`:

```sql
-- A: queue index
CREATE INDEX idx_outbox_record_status_created_id_incomplete
    ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key)
    WHERE completed_at IS NULL;
-- B1: per-key index
CREATE INDEX idx_outbox_record_key_created_id_incomplete
    ON outbox_record (record_key, created_at, id)
    WHERE completed_at IS NULL;
-- C: retry index
CREATE INDEX idx_outbox_record_status_retry_incomplete
    ON outbox_record (status, next_retry_at) INCLUDE (partition_no)
    WHERE completed_at IS NULL;
-- D3: count index
CREATE INDEX idx_outbox_record_status_partition_incomplete
    ON outbox_record (status, partition_no)
    WHERE completed_at IS NULL;
-- extended statistics
CREATE STATISTICS outbox_record_status_completed_stx (mcv)
    ON status, completed_at FROM outbox_record;
```

Main had five secondary indexes (section 4). The final set has four, all partial. Measured size effect (`experiments/CMP-main-vs-jdbc-opt-pg.md`): indexes S2 430 MB -> 77 MB, S4 836 -> 433 MB, S9 after churn 846/776 -> 321/285 MB.

| Index | Serves (P1/P2) | Query access path |
|---|---|---|
| A `(status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key)` | P1 `keys_strict`, `keys_nonstrict` (outer scan, in `(created_at, id)` order, stops at `LIMIT`); P2 `oldest_created` | index-only range on `status`, early stop |
| B1 `(record_key, created_at, id)` | P1 `pages` (probe per key), predecessor `NOT EXISTS` of `keys_strict`, `keys_nonstrict`, `oldest_due_strict` | per-key range probe |
| C `(status, next_retry_at) INCLUDE (partition_no)` | P2 `oldest_due_strict` (outer scan), `oldest_due_nonstrict`, `count_statuses`/`count_partitions` (`INCLUDE (partition_no)`) | ordered by `next_retry_at`, stops at first row |
| D3 `(status, partition_no)` | P2 `count_partitions` (index-only count), `count_statuses` | index-only count |
| statistics `(mcv) ON status, completed_at` | planner estimates for A with custom plans | no runtime cost, +8.3 % `ANALYZE` time (`experiments/E1x-v1-ablation-leave-one-out.md`) |

Writes (`insert`, `upd_*`, `delete_by_id`) use only the primary key for access; secondary indexes are maintained, not read (`experiments/E05-drop-indexes.md`).

Final vs main, headline numbers (two passes, `experiments/CMP-main-vs-jdbc-opt-pg.md`): S10 throughput +23 % (S2) and +22 % (S4); S4 `upd_complete` WAL 21 309 -> 8 999 bytes (-59 %); `oldest_due_strict` S8-S4 532 -> 0.75 ms; `keys_nonstrict` S4 687 -> 1.11 ms; S5 `keys_strict` 10.1 -> 1.28 ms. `keys_strict` at partial ownership is unchanged against current main (it was already fixed by GH-551).

---

## 2. Per index

### 2.1 Index A: `(status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL`

What it serves. The outer scan of `keys_strict` and `keys_nonstrict`: "oldest due NEW records first", cut by `LIMIT :limit`. Also `oldest_created` (`ORDER BY created_at LIMIT 1`).

Mechanism
- Column order `status, created_at, id`: `status = :status` is an equality prefix, so the scan walks only NEW entries already sorted by `(created_at, id)`, which is exactly the `ORDER BY`. The `LIMIT` stops after about 10 qualifying rows instead of reading the backlog. Without a status prefix, FAILED entries sit inside the same ordered range and have to be skipped: the earlier `created_at`-leading partial index removed 133 903 non-NEW rows before finding 10 on S7 (`experiments/E02-status-created-v1.md`, plans of `v1/.../S7-R1`), costing 12.2 ms versus 0.133 ms with the status-leading form.
- `completed_at IS NULL` predicate: COMPLETED rows (98 % of S2) never enter the index. The index size then depends on the backlog, not on retained history (S2: 3.8 MB; `experiments/R3X-screen-extension.md`). On completion the updated row version no longer satisfies the predicate, so no new entry is written (general mechanism; the measured result is the S4 `upd_complete` WAL drop in `experiments/CMP-main-vs-jdbc-opt-pg.md`). The predicate is a literal `completed_at IS NULL` in the SQL, not `status = 'NEW'`. A `status = 'NEW'` predicate cannot be matched by the driver's generic plans (`status = $1`), which is why `completed_at IS NULL` was chosen (`STATE.md` decision list, `experiments/E02-status-created-v1.md`).
- `INCLUDE (partition_no, next_retry_at, record_key)`: the outer query filters on `partition_no IN (...)` and `next_retry_at <= :now`, and it returns `record_key`. With these in the index the scan is index-only, with zero heap fetches (`experiments/E02-status-created-v1.md`, S7 plan: `Heap Fetches: 0`). They are payload, not keys, because ordering must remain `(created_at, id)` (see rejected `partition_no` below).

Alternatives tested and rejected
| Alternative | Result | Evidence |
|---|---|---|
| `(created_at, id) ... WHERE completed_at IS NULL` (status as INCLUDE, earlier design) | status not an index condition; FAILED rows scanned and discarded; `keys_strict` S7 13.3 ms vs 1.2 ms | `experiments/E02-status-created-v1.md`, `STATE.md` E02 row |
| Non-partial `(status, created_at, id)` (E02a, E01a) | same read performance as the partial form, but +143 MB; index-only scan lost because `completed_at` comes from the heap | `STATE.md` E01/E02 rows, `experiments/E02-status-created-v1.md` |
| `partition_no` as a key column, leading or second (`PA1`, `PL-A`, `PAC`) | `keys_strict` S8-S4 692-704 ms vs 0.90 ms; S16-S4 215 ms. A global `ORDER BY created_at` over several partitions loses index order, so the plan reads 250 815 rows, merge anti-joins and top-N sorts (750 501 buffers) | `experiments/R3X-screen-extension.md`, `experiments/PA-partition-key.md` |
| `partition_no` as trailing key (`PA2`) | no gain over INCLUDE | `experiments/R3-screen.md` |
| A2 `INCLUDE (partition_no)` only, A3 no INCLUDE | S8-S5 `keys_strict` 253 vs 165 ms (23 601 heap reads), saves 63 MB on S4. A2 gave nothing over A3 | `experiments/R3C-confirm.md`, `experiments/R3X-screen-extension.md` |
| Without any status-leading queue index (E13) | `keys_strict` S4 1.25 -> 808 ms, S10 p4 1110 -> 659 rec/s | `experiments/E1x-v1-ablation-leave-one-out.md` |
| Two queue indexes side by side (E02c) | no gain; the second is pure write overhead | `experiments/E02-status-created-v1.md` |
| E08a per-partition `LATERAL` for key selection with `(partition_no, status, created_at, id)` | P1 worse, `oldest_created` 23 ms; with A unchanged `keys_*` up to 1185 ms | `experiments/R4-screen.md` |

The index alone does not help main's old aggregation SQL: S4 `keys_strict` main SQL + queue index 4089 ms vs 4272 ms; v1 SQL without the index 807 ms; both together 1.25 ms (`experiments/E2x-v1-ablation-add-one.md`). Index and query rewrite only work together.

Write cost and size. S2 3.8 MB; S4 total of all four indexes 451 MB. A is the widest entry (four columns plus INCLUDEs). A3 (no INCLUDE) saves 63 MB on S4 but breaks index-only (above). INCLUDE columns changing on retry means `upd_retry` is not HOT for this index (inference; `ideas.md` E10/E14 not run as separate experiments).

**Portable idea / PG-specific part.** Portable: put the equality column (`status`) first, then the sort order of the query (`created_at, id`), and cover the filter and output columns so the scan needs no table lookup and can stop at `LIMIT`. PG-specific: the `WHERE completed_at IS NULL` partial predicate (no partial indexes in MariaDB/InnoDB, general knowledge) and `INCLUDE` (InnoDB has no INCLUDE clause, but trailing key columns can play the same covering role, at the price that they become part of the key and of every secondary entry; general knowledge). Without a partial index, whether COMPLETED rows can be kept out of the index is the central open question.

### 2.2 Index B1: `(record_key, created_at, id) WHERE completed_at IS NULL`

What it serves. (1) The predecessor check `NOT EXISTS (earlier ...)` in `keys_strict`, `keys_nonstrict` (variant with `status`/`next_retry_at`), and `oldest_due_strict`. (2) The per-key probes of `pages` (`unnest` + `LATERAL`).

Mechanism
- `record_key` equality, then `created_at` range, then `id` tie-break: the condition `earlier.created_at <= o.created_at AND (earlier.created_at < o.created_at OR (= AND earlier.id < o.id))` is one range on the index, and for the typical key (one incomplete record) it returns "no earlier row" after a single probe. The `<=` conjunct is logically implied but lets the planner see a plain range (section 3.1).
- `completed_at IS NULL` predicate: both uses only need incomplete rows (a COMPLETED predecessor never blocks). Entries of retained history are not stored, so the index stays in the order of megabytes. Build size 1.6 MB versus 118 MB for the full `(record_key, completed_at, created_at, id)` index; after S9 churn 49-79 MB (entries of completed rows persist until vacuum) versus 216-247 MB (`experiments/E04-partial-record-key.md`).
- `id` as the last key column: gives index order `(created_at, id)` for `pages` per key (no sort) and index-only predecessor probes at ties. Its measured read benefit was none (K12/E12), but with the partial predicate the cost is small. Without `id`, `pages` gets an `Incremental Sort` and the probe becomes `Index Scan` (`experiments/E1x-v1-ablation-leave-one-out.md`, E12).

Alternatives tested and rejected
| Alternative | Result | Evidence |
|---|---|---|
| Main's `(record_key, created_at)` kept | no read effect; +67 MB, +2-4 % WAL per write | `experiments/E1x-v1-ablation-leave-one-out.md` (E11), `experiments/E2x-v1-ablation-add-one.md` (E21: -67 MB, WAL about -3 %) |
| Full `(record_key, completed_at, created_at[, id])` | same P1 reads as partial; 118 MB vs 1.6 MB; WAL on `upd_complete` +7 % | `experiments/E04-partial-record-key.md` |
| Without `id` (K12) | no P1/P2 loss, -44 MB; superseded by the partial index | `experiments/E05-drop-indexes.md` |
| B2 `INCLUDE (status, next_retry_at)` | `pages` S8-S5 60 vs 70 ms, below threshold; S9 equal | `experiments/R3C-confirm.md` |

Write cost and size. With partial B1 the record-key index drops from 118 MB to 1.6 MB at load; E04 total indexes 277 -> 160 MB, WAL per `upd_complete` 7552 -> 7015 (-7 %), insert unchanged (`experiments/E04-partial-record-key.md`). A known accepted P3 cost: `delete_by_key_status` S4 0.12 -> 413 ms in E04 (no usable key index for `record_key + status`), 278 ms on the final branch (`experiments/CMP-main-vs-jdbc-opt-pg.md`).

Important interaction (D3 / `pages`). B1 and the status partial indexes must keep the same `completed_at IS NULL` predicate. If the status index is non-partial (or has a different predicate text), the planner builds a `BitmapAnd` of the key index and the full-status index, which scans every NEW entry per key and removes no rows: `pages` S8-S5 62 -> 133 ms (`experiments/R5-pages-bitmapand.md`, eight index sets; the rule is inferred, the planner source was not read). The ADR records this as a design constraint.

**Portable idea / PG-specific part.** Portable: a composite `(record_key, created_at, id)` (or any index with `record_key` first and the ordering columns after) turns both the per-key page read and the "is there an earlier incomplete record" check into short index range probes; also "an index that exists only to answer incomplete-row questions should not contain the retained history" (the idea behind both the 118 MB -> 1.6 MB drop and the dropped `(record_key, completed_at, ...)`). If MariaDB cannot exclude COMPLETED rows from the index, the `(record_key, completed_at, created_at, id)` form (completed_at as second key so `IS NULL` is a range) is the closest analogue (`E04-partial-record-key.md`, "Portability" line). PG-specific: the partial predicate, and the BitmapAnd interaction.

### 2.3 Index C: `(status, next_retry_at) INCLUDE (partition_no) WHERE completed_at IS NULL`

What it serves. P2 `oldest_due_strict` (outer scan ordered by `next_retry_at`, stops at the first row that passes the predecessor check) and `oldest_due_nonstrict` (`MIN(next_retry_at)`). Under the literal count forms it also serves `count_partitions` (via `INCLUDE (partition_no)`) when D3 is absent.

Mechanism
- `(status, next_retry_at)`: equality on status, then ordering by `next_retry_at`, so `MIN`/`ORDER BY next_retry_at LIMIT 1` reads one entry. This is a different sort order from A (`created_at`), which is why A cannot replace it: with the queue index as the only access path `oldest_due_strict` became a parallel seq scan (0.6 -> 259-722 ms, `experiments/E05-drop-indexes.md` A6 row, plan in `A6/.../S8-S4-R2/plans/oldest_due_strict.txt`: the partial queue index carries `next_retry_at` only as INCLUDE).
- `INCLUDE (partition_no)`: the query filters `partition_no IN (...)`; having it in the entry keeps the scan index-only. Making it a key column (`PC1`: `(status, partition_no, next_retry_at)`, `PL-C`: `(partition_no, status, next_retry_at)`) lost the global `next_retry_at` order: `oldest_due_strict` S8-S4 198 ms (`experiments/R3X-screen-extension.md`).
- Partial: the retry index otherwise holds ~98 % COMPLETED entries.

Alternatives tested and rejected
| Alternative | Result | Evidence |
|---|---|---|
| No C (A2/A5/A6-type drops) | `oldest_due_strict` S8-S4 0.7 -> 527 ms (A2); without C in set `UAB1` 198 ms | `experiments/E05-drop-indexes.md`, `experiments/R3-screen.md` |
| C2 `INCLUDE (partition_no, record_key, created_at, id)` (index-only outer for the predecessor check) | worse: S8-S4 `count_*` 124-129 vs 50 ms, S9 index 401 vs 343 MB | `experiments/R3C-confirm.md` |
| C3 `(status, next_retry_at)` (no INCLUDE) | -9.6 MB on S4 but breaks `count_partitions` when D3 is absent (119 vs 48 ms); with D2 only plan-mode flip noise | `experiments/R3X-screen-extension.md`, `experiments/R3C-confirm.md` |
| Main's `idx_status_retry` (non-partial) | superseded by C | `experiments/U-index-set.md` |

Write cost. In the screen there was no WAL saving from dropping the old `(status, next_retry_at)` alone (A2: -0.4 to -1.7 %, noise; `experiments/E05-drop-indexes.md`), so C's cost is tolerable; its rationale is the P2 query, not savings.

**Portable idea / PG-specific part.** Portable: `MIN`/ordered-first-row queries need an index whose key order equals the requested order (`status` equality prefix, then `next_retry_at`), and the extra filter column (`partition_no`) belongs at the end. PG-specific: partial predicate, INCLUDE, and the planner rewriting `MIN()` into `Limit > Index Scan` (`experiments/E1x-v1-ablation-leave-one-out.md`, E1M plans). Do not assume MariaDB rewrites the same way; it does handle `MIN` on an index prefix as a "Select tables optimized away" case in many cases (general knowledge, to be verified).

### 2.4 Index D3: `(status, partition_no) WHERE completed_at IS NULL`

What it serves. P2 `count_partitions` (`COUNT(*) ... partition_no IN (...) AND status = :status AND completed_at IS NULL`) as an index-only count, and a more compact path for `count_statuses`.

Mechanism. `count_partitions` is called per metrics refresh and counts rows by (status, partition set). A narrow `(status, partition_no)` partial index makes it an index-only scan over backlog entries only: S8-S4 48.5 -> 10.1 ms, S16-S4 47.8 -> 2.85, S8-S5 16 -> 1.8, S8-S6 22.9 -> 2.6 ms versus the set without D3 (`experiments/R5-d2-literal.md`, `experiments/R4-screen.md`).

Why partial and why partial exactly like B1. Non-partial D2/D3-style indexes `(status, partition_no)` doubled `pages` on hot keys through `BitmapAnd`: S8-S5 132.5/134.3 ms vs 62.6/62.4 ms (full `(status, partition_no)` 3b vs 3a), and `count_statuses_all` is the only thing the full index helps (73 vs 301-321 ms on S4). D3 gets the count gain, no `BitmapAnd` plan in any D3 `pages` plan (`experiments/R5-d2-literal.md`; mechanism in `experiments/R5-pages-bitmapand.md`: the planner multiplies selectivities of `record_key` and `status` as if independent, estimating 788 rows where 2000 are returned; with equal partial predicates the AND path is not built, inferred).

Alternatives tested and rejected
| Alternative | Result | Evidence |
|---|---|---|
| D1 `(status)` | `count_partitions` 210 vs 10 ms S8-S4 | `experiments/R3-screen.md` |
| D2/3b non-partial `(status, partition_no)` | `pages` x2 on hot keys (BitmapAnd), +14 MB, +5-6 % WAL, +7 % insert WAL; only gain `count_statuses_all` | `experiments/R5-d2-literal.md`, `experiments/R3C-confirm.md` |
| `PD` `(partition_no, status)` | `count_statuses` 107 vs 27.7 ms, no `count_partitions` gain | `experiments/R3X-screen-extension.md` |
| No D at all (`UABC-L`, literal counts served by A/C) | `count_partitions` S8-S4 48.8 ms, `count_statuses` 50 ms vs 10 / 39 ms; zero extra write cost | `experiments/R5-d2-literal.md` (second place) |

Write cost. Measured on S4: index +7 MB (457 vs 450 MB), insert WAL 7201 vs 6833 bytes (+6 %), `upd_complete` WAL unchanged (8997 vs 8997). S2 no difference (`experiments/R5-d2-literal.md`, `experiments/R4-screen.md`). Accepted: S10 on S4 1230 vs 1118 (ranges overlap, no difference established).

Open limit. `count_statuses_all` (COMPLETED configured in `record-statuses`) has no index with D3 or without: parallel seq scan, 301-323 ms on S4, once seen 1.8-2.0 s with identical plan and buffers (`experiments/R5-d2-literal.md`, `experiments/U-index-set.md`). It is the strongest argument for a non-partial `(status, partition_no)` on PG.

**Portable idea / PG-specific part.** Portable: a narrow covering index `(status, partition_no)` turns counting into an index-only scan. PG-specific: partial form; index-only scans and the visibility map (heap fetches vanish only when pages are all-visible; MariaDB's covering-index scan has a different mechanism, general knowledge); the `BitmapAnd` hazard (MariaDB has `index_merge` intersection, which may behave similarly: hypothesis, not measured).

### 2.5 Extended statistics `(mcv) ON status, completed_at`

What it serves. Planner row estimates for predicates `status = 'NEW' AND completed_at IS NULL` on a table that is mostly COMPLETED. Not an index; no runtime or write cost.

Mechanism. Without it the planner treats `status` and `completed_at` as independent and underestimates the NEW rows by two orders of magnitude (estimate 11 vs 1000 actual on S2). With a wrong estimate the planner picks `Sort > Nested Loop > Index Scan idx_status`-type plans for `keys_*`, instead of the early-stopping partial scan. This happens only under custom plans (literal parameters); with forced generic plans (`status = $1`) the MCV list is not used and no difference was seen (`experiments/E1x-v1-ablation-leave-one-out.md`, "E14 details").

Evidence for keeping it: S2 `keys_strict` 2.14 -> 4.32 ms and `keys_nonstrict` 2.12 -> 4.68 ms without it, S10 p4 1110 -> 770 rec/s (`experiments/E1x-v1-ablation-leave-one-out.md`). On the final index set: without it S2 `keys_nonstrict` 1.68/1.75 vs 1.07/1.08 ms in 3 of 3 S2 runs, different plan; S8-S2 no difference (`experiments/U-index-set.md`, index table; this corrects "drop candidate" in `experiments/R3C-confirm.md`). Alone on main's SQL it does nothing (`experiments/E2x-v1-ablation-add-one.md`, E23). Cost: `ANALYZE` +8.3 % (416 -> 450 ms on 1M rows).

**Portable idea / PG-specific part.** Portable idea: check the optimizer's cardinality estimate for "status = NEW AND completed_at IS NULL" on a table dominated by COMPLETED rows; a wrong estimate can change the plan. PG-specific: `CREATE STATISTICS` (MariaDB has no multi-column statistics object; it has engine-independent statistics and histograms per column, general knowledge; correlation between the two columns would need another approach).

---

## 3. Per query

All final SQL below is the `Default` form plus the PG override (`LIMIT :limit` clause, `unnest`/`LATERAL`, literal counts). `earlier` is a second reference to the table.

### 3.1 `keys_strict` (`findRecordKeysInPartitions`, strict, default)

Main (GH-551 form, current `main`):
```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND NOT EXISTS (SELECT 1 FROM outbox_record older
                  WHERE older.record_key = o.record_key AND older.completed_at IS NULL
                    AND (older.created_at < o.created_at
                         OR (older.created_at = o.created_at AND older.id < o.id)))
ORDER BY o.created_at, o.id LIMIT/setMaxRows
```
Final:
```sql
SELECT o.record_key FROM outbox_record o
WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now
  AND o.completed_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM outbox_record earlier
                  WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL
                    AND earlier.created_at <= o.created_at
                    AND (earlier.created_at < o.created_at
                         OR (earlier.created_at = o.created_at AND earlier.id < o.id)))
ORDER BY o.created_at, o.id LIMIT :limit
```

Why each rewrite
1. No `GROUP BY record_key ... ORDER BY MIN(created_at)` (the pre-GH-551 main form). The anti-join leaves at most one record per key (the oldest incomplete one), so grouping is unnecessary. A `GROUP BY` forces the planner to read all eligible rows before the limit applies. With a 1M backlog `keys_strict` was 4.27 s (S4) and 14.5 s (S5); without it plus index A, 1.25 ms (`experiments/E2x-v1-ablation-add-one.md`, section "Interactions summary"; plan `Sort > Aggregate > Hash Join > Seq Scan` vs `Limit > Nested Loop Anti Join > Index Only Scan`, `experiments/E1x-v1-ablation-leave-one-out.md`).
2. `o.completed_at IS NULL` on the outer row. Logically redundant (status NEW implies it). It is there so the planner can prove that partial index A applies; without it A is not eligible (`experiments/E04-partial-record-key.md`, MIN: `oldest_due_strict` without the literal became a seq scan).
3. `earlier.created_at <= o.created_at`. Implied by the next condition, but it gives the planner a simple range on `(record_key, created_at)` for B1, instead of an OR expression it cannot turn into an index range (ADR 0005 text in `DefaultJdbcOutboxRecordQueries.kt` KDoc). Result is identical.
4. `ORDER BY o.created_at, o.id LIMIT :limit` ordered like A, so the outer scan stops early. Early stop is the central point (S4: 4272 ms -> 1.25 ms together with A).

Evidence against current main: unchanged at partial ownership (S8-S2/S4/S6/S7 within noise; S8-S5 163 vs 196 ms, -16 %, no difference); S5 full ownership 10.1 -> 1.28 ms (`experiments/CMP-main-vs-jdbc-opt-pg.md`).
Known accepted cost: S8-S5 `keys_strict` ~163 ms: partial ownership with 18 000 candidate rows probed and 166k rows filtered by partition; the scan cannot stop early when owned partitions hold fewer keys than `:limit`. S5 at full ownership is 1.3 ms (`experiments/R3C-confirm.md`, `experiments/R4-screen.md`).

Rejected forms
- K3: cap the outer candidates (`ORDER BY created_at, id LIMIT 1000`) before the anti-join. S8-S5 162.55 -> 4.96 ms, but it can return fewer keys than `:limit` (or none) if the 1000 oldest candidates are blocked behind FAILED heads or share one key; the user rejected it, no fallback form is to be tested (`experiments/R4-screen.md`, `STATE.md` 2026-11-03).
- E08/E08a/E08A per-partition `LATERAL` with `LIMIT :limit`: `keys_*` regress up to 1185 ms (`experiments/R4-screen.md`).
- `partition_no`-leading queue index to use the partition filter in the index: 692-704 ms (`experiments/R3X-screen-extension.md`).

**Portable idea / PG-specific part.** Portable: drop `GROUP BY`/`MIN` when the anti-join already guarantees one row per key; order by the index order; use a SARGable range for the tie-break (`created_at <=` first); add the redundant `completed_at IS NULL` so a covering/filtered index is eligible. PG-specific: partial index, index-only scans, planner choices in `Nested Loop Anti Join`. MariaDB hypothesis (not measured): check `NOT EXISTS` is executed as anti-join/semijoin (`FirstMatch`/materialization) and that the outer `ORDER BY ... LIMIT` can use index order (MariaDB's `ORDER BY ... LIMIT` optimization with index on `(status, created_at, id)`).

### 3.2 `keys_nonstrict`

Main: `SELECT o.record_key ... WHERE partition_no IN, status, next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at)` (no predecessor check, GROUP BY retained).
Final: same shape as 3.1 but the inner `NOT EXISTS` is restricted to earlier **due** records of the same status: `earlier.status = :status AND earlier.next_retry_at <= :now AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND (...)`.

Why. The non-strict mode wants "the first due record per key", ignoring a not-yet-due or FAILED predecessor. This rewrite returns the same set as `GROUP BY ... MIN` (equivalence 26 PASS on S2, S5, S7, `experiments/CMP-main-vs-jdbc-opt-pg.md`) but removes aggregation and allows early stop: S4 687 -> 1.11 ms, S8-S4 294 -> 0.90 ms, S8-S5 22.3 -> 1.05 ms (`experiments/CMP-main-vs-jdbc-opt-pg.md`).

Cautions seen in the experiments
- The rewrite on its own regressed small backlogs: S2 1.35 -> 4.19 ms, S8-S2 0.99 -> 1.82 ms, because a `Sort` of all 1000 NEW rows plus a probe per row was chosen instead of main's single `HashAggregate` (`experiments/E2x-v1-ablation-add-one.md`, E24). With the status-leading partial A it stayed about 1.0-1.1 ms. Do not ship the rewrite without the queue index.
- With the earlier index design (status not leading) the early-stop scan walked FAILED entries: S7 12.8 vs 8.8 ms; fixed by the status-leading A (`experiments/E1x-v1-ablation-leave-one-out.md`, V1-5).
- Statistics object matters here (S2 1.68 vs 1.07 ms, section 2.5).

**Portable idea / PG-specific part.** Portable: replace per-key aggregation by "no earlier due record" so the scan can stop at `LIMIT`. Validate on a small backlog too, where the old aggregate is cheaper. PG-specific: plan flips driven by estimates (section 2.5); `NOT EXISTS` probe depends on B1.

### 3.3 `pages` (`findIncompleteRecordPages`, bulk load by keys)

Main: `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id`.

Final (P-KK2 form):
```sql
SELECT o.*
FROM (SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k) k
CROSS JOIN LATERAL (
  SELECT r.* FROM outbox_record r
  WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL
  OFFSET 0) o
ORDER BY k.k, o.created_at, o.id
```
(The `ORDER BY` after `OFFSET 0` is explicit on the outer query; no inner sort.)

Why each element
- `unnest ... CROSS JOIN LATERAL`: forces exactly one B1 probe per key. In the processing loop (S10, 4 pollers, 60 s) the `IN` and `= ANY` forms flipped, in many passes, to custom plans that walk queue index A or C with `record_key` as a filter (pages p50 5-17 ms instead of ~1 ms); `unnest` + `LATERAL` was fast in 8 of 8 passes and 0 slow plans in 32 S10 runs (`experiments/R3-stage0-pages-loop.md`, `experiments/R3X-screen-extension.md` X0). The earlier verdict "no override needed" (isolated bench, E1P) was withdrawn because isolated cells on a freshly analyzed table do not show this.
- `OFFSET 0`: a fence that stops the planner from merging the subquery into an ordinary join; merged, under load it picked a Hash Join over the full-status partial index reading all NEW rows. The `P-KK3` form (no fence) cost S10 throughput 1209 -> 860 rec/s, `pages` p50 11.6-26.2 ms, 3 446-4 329 slow plans per run (`experiments/P-KK2-pages-without-inner-sort.md`).
- `completed_at IS NULL` in the inner SELECT: lets B1 apply.
- Top-level `ORDER BY k.k, o.created_at, o.id`: makes order inside each key guaranteed by SQL rather than by the nested loop happening to preserve the inner order. Order across keys does not matter (the repository groups rows by key in memory, `experiments/R5-pages-form.md`), so `k.k` first costs an Incremental Sort on a presorted key only. `P-KK2` vs reference: no difference by threshold (S8-S5 67/72 vs 61/64 ms); `P-UNO` (sort by `o.record_key`) +44 % on S8-S5.
- No inner `ORDER BY` (`P-KK2` vs `P-KK`): one sort instead of two on hot keys (S8-S5 67/72 vs 76/76 ms).

Evidence against main on the final branch: S10 on S2 `pages` p50 6.0-8.5 -> 0.70-1.27 ms, 3 031-4 550 slow plans per run on main vs 0; S8-S5 101 -> 60 ms (`experiments/CMP-main-vs-jdbc-opt-pg.md`). Other labels: no difference.

Rejected forms
| Form | Result | Evidence |
|---|---|---|
| `IN (...) ORDER BY record_key, created_at, id` (`PF-IN`/`P-IN`) | 2 slow S10 runs of 8; S8-S5 85.7 vs 62.6 ms; on-disk merge sort of 18 000 rows | `experiments/R5-pages-form.md` |
| `= ANY(ARRAY)`, no ORDER BY, client sort (`PF-ANY`, `PF-NS`) | slow passes in most S10 runs | `experiments/R3-stage0-pages-loop.md`, `experiments/U-pages-client-sort.md` |
| `P-KK3` (no `OFFSET 0`, no inner ORDER BY) | merged join, -27 % S10 throughput | `experiments/P-KK2-pages-without-inner-sort.md` |
| `P-UNO` (top-level `ORDER BY o.record_key`) | +44 % S8-S5 | `experiments/R5-pages-form.md` |
| Removing `ORDER BY` to avoid the sort | does not fix the plan flip; `PF-NS` slow in 6 of 8 passes | `experiments/R3-stage0-pages-loop.md` |
| Non-partial `(status, ...)` index next to B1 | `BitmapAnd`, pages x2 (section 2.2) | `experiments/R5-pages-bitmapand.md` |

The branch ref's `PostgresJdbcOutboxRecordQueries.kt` holds this form, with a code comment that `OFFSET 0` must not be removed (a fence against the `P-KK3` plan).

**Portable idea / PG-specific part.** Portable: bulk "read all incomplete records for these N keys" is cheapest as one index range probe per key (B1 prefix `record_key`), not as one statement whose plan depends on estimates; pin the join order or use a derived key table if the optimizer starts walking the status index instead. PG-specific: `unnest`, `LATERAL`, `OFFSET 0` fence, custom/generic plan caching of the JDBC driver. MariaDB hypothesis: the equivalent failure mode is `IN (...)` choosing the status index; test whether `IN` with a long list on `(record_key, created_at, id)` keeps the key index under concurrent load. Plan-cache flips of this kind are PG/driver specific (general knowledge: MariaDB does not have generic plans).

### 3.4 Writes: `insert`, `upd_complete`/`upd_retry`/`upd_fail`, `delete_by_id`

SQL is unchanged from main (`updateProcessingState` and `insertRecord` are identical in `DefaultJdbcOutboxRecordQueries.kt`; `delete_by_id` by primary key). The only change is the index set that each write must maintain.

Evidence (`experiments/CMP-main-vs-jdbc-opt-pg.md`): S4 `upd_complete` WAL 21 309/23 038 -> 8 999/8 997 bytes (-59 %); S2 7 815 -> 6 294 (-20 %, below threshold); insert WAL unchanged (S4 7 388 -> 7 244); latency p50 no consistent difference. Per-write latency was never the signal: WAL bytes, index size and throughput (S10 +22/+23 %) are.

Mechanism (general reasoning, partly inferred): completing a row makes the new row version fail the partial predicate, so the four partial indexes receive no new entries; on main every status/partition/record-key index received a new entry. Insert adds an entry to all four partial indexes (NEW rows satisfy the predicate), so insert cost depends on the index count (S2: all-partial 4 indexes vs 5 full), not on the table size.

Key results while choosing: removing indexes brought the clearest write-side gains: MIN set (PK + 2 partial indexes) cut WAL 9 % (insert) and 16 % (`upd_complete`) but broke P2 (section 4/5) (`experiments/E04-partial-record-key.md`). E04 alone: `upd_complete` WAL -7 %. UUIDv7 ids: S4 insert WAL 6 833 -> 1 564 bytes (not adopted in the final SQL; separate decision, section 5).

Noise. `upd_retry`/`delete_by_id` "regressions" on S4 did not reproduce (`experiments/U-index-set.md`, corrections). WAL needs two or more runs: single outliers 6 170 vs 7 509 (v1c S2).

**Portable idea / PG-specific part.** Portable: every index is paid on insert and on state change; measure WAL/redo volume and update latency for every DDL change, and keep only indexes that a P1/P2 query uses. PG-specific: partial-predicate indexes skipping completed entries; HOT updates are impossible once an indexed column or an INCLUDE column changes (general knowledge: InnoDB updates secondary entries in place of rows via delete-mark + insert when indexed columns change, so the mechanism differs).

### 3.5 `count_statuses` (`countByStatuses`, NEW/FAILED)

Main: three `SELECT COUNT(*) ... WHERE status = :status` per refresh (one per status), including COMPLETED.
Final (PG override): `SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status`, used only if COMPLETED is not requested; otherwise the default text without the literal.

Why
- One grouped statement instead of several counts; `completed_at IS NULL` lets D3/C/A serve the count over backlog entries only. Per refresh: S8-S2 42 -> 1.4 ms, S8-S4 82 -> 40 ms, S8-S5 33 -> 13 ms, S8-S6 40 -> 13, S8-S7 39 -> 28 (`experiments/CMP-main-vs-jdbc-opt-pg.md`, computed by adding cells; main's three counts include COMPLETED).
- The COMPLETED gauge is no longer collected by default (ADR decision 3). With COMPLETED requested the literal cannot be used: it would silently count zero. The final code branches on the requested set. Cost of the COMPLETED form: S8-S4 254-321 ms per refresh (parallel seq scan), S8-S2 147-175 ms (`experiments/CMP-main-vs-jdbc-opt-pg.md`).
- A literal-only override that dropped the status check was found to be a correctness trap: the MIN set returned no COMPLETED group at all (`experiments/E04-partial-record-key.md`, "What breaks if count_statuses is called with COMPLETED").

Rejected: relying on non-partial `(status)`/D2 for the count (non-partial `(status, partition_no)` doubled `pages`; section 2.4). `V1-10` (grouped query) alone was "no difference" against two single counts; gain comes from fewer statements and the literal (`experiments/E1x-v1-ablation-leave-one-out.md`).

Plan-mode note. The three `count_*` queries share one prepared statement text; the generic plan is a parallel `Gather` whose ~21 ms startup is environment-specific and flips on a cost tie (`experiments/R2-plan-modes.md`). Interpret ~22 ms cells with `plancache.csv`.

**Portable idea / PG-specific part.** Portable: one grouped query per refresh; do not collect COMPLETED counts by default because they scale with retained history; if an index excludes COMPLETED rows then the SQL must never be asked about COMPLETED through it. PG-specific: the partial index; index-only counting relies on the visibility map.

### 3.6 `count_partitions` (`countRecordsByPartitions`)

Main: `SELECT COUNT(*) ... WHERE partition_no IN (:partitions) AND status = :status` served by main's `(partition_no, status, next_retry_at)`.
Final: same plus `AND completed_at IS NULL` for NEW/FAILED (COMPLETED keeps the default text).

Why. With D3 the count is an index-only scan over incomplete entries (S8-S4 10.1 ms vs main 16.1 ms; S8-S6 2.5 vs 5.6). Without D3 (`UABC-L`) the literal form is served by A or C at 48.8 ms (S8-S4).
Evidence for the choice of the three-column main index being dropped: A5 (drop `(partition_no, status, next_retry_at)`) cost S8-S4 16 -> 209 ms and gained 6.6 % WAL (`experiments/E05-drop-indexes.md`); D3 replaces it with a narrower partial index.
Accepted cost: S8-S7 `count_partitions` 0.71 -> 1.35 ms (regression, `experiments/CMP-main-vs-jdbc-opt-pg.md`). Forced generic plans: 317 ms on S8-S4 in `UABC-L`, default mode stayed custom (`experiments/R3C-confirm.md`).
Deprecated metrics module is not a target: its `count_partition_single`/`count_new` are 100-400x slower on the final branch (106-200 ms vs 0.24-0.55 ms) because `countByPartitionStatus`/`countByStatus` have no literal; the user accepted this (`experiments/CMP-main-vs-jdbc-opt-pg.md`, "Deprecated metrics module"). Note: a MariaDB design must decide for itself whether to override those two statements.

**Portable idea / PG-specific part.** Portable: counts by `(status, partition)` want a narrow index with both columns. PG-specific: partial form, index-only scan and plan-cache flips.

### 3.7 `oldest_created` (`findOldestCreatedAtByPartitions`)

Main: `SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:p) AND status = :status`.
Final: `SELECT created_at ... WHERE partition_no IN (:p) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT :limit`.

Why. Served by A (ordered by `created_at` per status, first qualifying row). Versus main: S8-S4 211 -> 0.41 ms on the older baseline (`experiments/E2x-v1-ablation-add-one.md`, E26 + queue index; `experiments/E1x-v1-ablation-leave-one-out.md` V1-6). `ORDER BY ... LIMIT` was preferred over `MIN` by the user's rule ("ORDER BY + row limit over MIN/GROUP BY when performance is the same", `STATE.md`).
Dependence on A: alone it is worse than main (S8-S4 211 -> 459 ms: `Limit > Gather Merge > Sort > Parallel Index Scan idx_status`, no created_at-ordered index) (`experiments/E2x-v1-ablation-add-one.md`). Needs the queue index.
Measured cost against current main: +0.2 ms in S8-S2..S8-S7 (0.19-0.29 -> 0.37-0.48 ms), above the threshold (`experiments/CMP-main-vs-jdbc-opt-pg.md`, Priority 2 table).
Preliminary note from the user (not in `docs/db-sql/`, a still-running PostgreSQL test): `MIN(created_at)` appears better than `ORDER BY created_at LIMIT 1` for `oldest_created` on PostgreSQL; the branch will be updated later. This agrees directionally with the +0.2 ms measured vs main and with E1M/R5 results for `oldest_due_nonstrict` (section 3.9). Treat as preliminary until the run is documented.

**Portable idea / PG-specific part.** Portable: `MIN(col)` and `ORDER BY col LIMIT 1` are equivalent for the optimizer when an index on `(status, col)` exists; the difference measured was planning/plan-cache behaviour, not execution. PG-specific: custom-vs-generic plan mode (section 3.9).

### 3.8 `oldest_due_strict` (`findOldestDueNextRetryAtByPartitions`, strict)

Main: `SELECT MIN(o.next_retry_at) ... WHERE partition_no IN, status, next_retry_at <= :now AND NOT EXISTS (older ...)`.
Final: `SELECT o.next_retry_at ... WHERE partition_no IN, status, next_retry_at <= :now, o.completed_at IS NULL AND NOT EXISTS (earlier ... created_at <= ...) ORDER BY o.next_retry_at LIMIT :limit`.

Why. `MIN` forced every due NEW row through the anti-join first; `ORDER BY next_retry_at LIMIT 1` walks C in order and stops at the first row whose predecessor check passes (usually the first). Results against main: S8-S4 532 -> 0.75 ms, S5 2660 -> 0.92 ms, S8-S5 498 -> 0.72, S8-S7 12.6 -> 0.60, S8-S2 1.58 -> 0.79 (`experiments/CMP-main-vs-jdbc-opt-pg.md`). This rewrite alone (on main's indexes) already gave -97 to -100 % (`experiments/E2x-v1-ablation-add-one.md`, E26), because main's `(status, next_retry_at)` index already provided the order; index C keeps that order but is partial.
Needs: C (no C: 198-527 ms) and the literal (without it a partial C is ineligible and PG seq-scans: `experiments/E04-partial-record-key.md`, MIN) and B1 for the predecessor probe.

**Portable idea / PG-specific part.** Portable: an `ORDER BY next_retry_at LIMIT 1` over an index on `(status, next_retry_at)` stops after the first row; the anti-join is only evaluated for candidates. PG-specific: partial index eligibility via the literal.

### 3.9 `oldest_due_nonstrict`

Main and final: `SELECT MIN(o.next_retry_at) ... WHERE partition_no IN, status = :status, next_retry_at <= :now` plus `AND o.completed_at IS NULL` in the final.

Why `MIN` was kept. E1M and R5 compared `ORDER BY next_retry_at LIMIT :limit` with `MIN`: identical index and execution (0.03-0.04 ms), yet `ORDER BY` was 0.3-0.5 ms slower in all 7 labels (S8-S4 0.27/0.37 vs 0.65/0.56 ms). Likely cause: plan-cache mode. `MIN` used generic plans in 16 of 20 timed reps, `ORDER BY ... LIMIT :limit` always replanned custom plans (0.4-0.5 ms planning for 0.05 ms execution) (`experiments/R5-oldest-due.md`, `experiments/R2-plan-modes.md`; "partly inferred"; literal `LIMIT 1` not tested). Same-performance condition of the user's rule not met, so `MIN` stays.

**Portable idea / PG-specific part.** Portable: when two query forms have the same execution plan, compare planning/parse overhead and prepared-statement behaviour before choosing the "cleaner" one. PG-specific: pgjdbc server-prepare after 5 executions and PG's custom/generic plan choice (no direct MariaDB equivalent; general knowledge).

---

## 4. Indexes dropped from main, and why

Main (current) secondary indexes (`schema/postgres/outbox-tables.sql` on `main`):

| Main index | Why dropped | Evidence |
|---|---|---|
| `idx_outbox_record_record_key_created (record_key, created_at)` | redundant with the key index; no read effect, -67 MB, -3 % WAL | `experiments/E2x-v1-ablation-add-one.md` (E21), `experiments/E1x-v1-ablation-leave-one-out.md` (E11) |
| `idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)` | replaced by partial B1; equal P1; 118 MB -> 1.6 MB | `experiments/E04-partial-record-key.md` |
| `idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at)` | replaced by D3 for counts; its WAL is the only measurable single-drop gain (-6.6 %); drop alone costs `count_partitions` 16 -> 209 ms, so D3 was added | `experiments/E05-drop-indexes.md` (A5), `experiments/R5-d2-literal.md` |
| `idx_outbox_record_status_retry (status, next_retry_at)` | replaced by partial C (`INCLUDE (partition_no)`); dropping without C costs `oldest_due_strict` x746 | `experiments/E05-drop-indexes.md` (A2) |
| `idx_outbox_record_status_created (status, created_at, id) INCLUDE (record_key, partition_no, next_retry_at, completed_at)` | replaced by partial A; `completed_at` in INCLUDE made it wide and kept COMPLETED entries: non-partial form gave the same reads but +143 MB; `INCLUDE (completed_at)` presumably served to evaluate `IS NULL` without a partial predicate (inferred) | `experiments/E01-status-created-main.md`, `experiments/E02-status-created-v1.md` |

Not dropped but never added: any index serving only P3 (retention, `delete_by_key_status`, `find_*_before`). P3 regressions are accepted (final vs main, `experiments/CMP-main-vs-jdbc-opt-pg.md`): S2 `find_failed_before` 0.29 -> 173 ms, S4 `delete_by_key_status` 0.14 -> 278 ms, `find_completed_before` and `delete_by_status_failed` slower or faster depending on label.

---

## 5. Rejected ideas (one line each)

- `partition_no` leading or second in A, C or the count index: loses global `created_at`/`next_retry_at` order; `keys_strict` 692-704 ms, `oldest_due_strict` 198-658 ms; end of key gives no gain. `experiments/R3X-screen-extension.md`, `experiments/PA-partition-key.md`. Not tested: an instance owning one partition.
- K3 (cap key candidates at 1000): rejected by the user, can return fewer than `:limit` keys. `experiments/R4-screen.md`, `STATE.md`.
- E08 per-partition `LATERAL` key selection: `keys_*` up to 1185 ms, `oldest_created` 23 ms. `experiments/R4-screen.md`.
- A3 (queue index without INCLUDE): S8-S5 `keys_strict` 253 vs 165 ms, 23 601 heap reads. `experiments/R3C-confirm.md`.
- A2, B2, C2, C3: no gain or loss. `experiments/R3X-screen-extension.md`, `experiments/R3C-confirm.md`.
- D1 `(status)`, D2/3b full `(status, partition_no)`, `PD`: see 2.4. `experiments/R3-screen.md`, `experiments/R5-d2-literal.md`.
- Dropping A2/A5/A6 sets (main indexes): A2 `oldest_due_strict` x746; A5 `count_partitions` x12.7; A6 and the MIN set (PK + 2 partial indexes + literals): WAL -9/-16 %, indexes -72 % but `oldest_due_strict` 0.6 -> 186-722 ms, `count_*` 119-122 ms. `experiments/E05-drop-indexes.md`, `experiments/E04-partial-record-key.md`.
- Per-table autovacuum tuning (E07) and `fillfactor = 85` (E07b): no WAL or throughput gain, S9 slower, table +14 %. `experiments/R4-screen.md`.
- UUIDv7 record ids (E40): not part of the final SQL/DDL; separate decision. S4 insert WAL 6 833 -> 1 564 bytes, `upd_complete` WAL 8 997 -> 1 938/2 441, no read or throughput change established; the larger primary key was a harness load-order artefact. Mechanism "time locality", inferred. `experiments/R5-uuidv7.md`, `experiments/R5-uuidv7-pkey.md`. General knowledge: ordered keys matter more for engines that cluster the table on the primary key; the MariaDB experiment should test it on its own.
- `pages` forms `IN`, `= ANY`, client sort, `P-UNO`, `P-KK`, `P-KK3`: section 3.3.
- `ORDER BY ... LIMIT` for `oldest_due_nonstrict`: slower than `MIN`. `experiments/R5-oldest-due.md`.
- Dropping the `completed_at IS NULL` check by using `status = 'NEW'` as predicate: unusable with generic plans (`STATE.md` decision, `experiments/E02-status-created-v1.md`).
- Non-partial count index to keep `COMPLETED` counts fast: `pages` x2 (BitmapAnd), so `count_statuses_all` stays a seq scan (300 ms to 2 s). `experiments/R5-d2-literal.md`.
- UABC-L without D (second place): P2 counts 4-5x slower, no write cost. `experiments/R5-d2-literal.md`.

---

## 6. Methodology lessons to reuse

General
1. Define priorities first (P1 hot path, P2 metrics, P3 retention) and a decision rule that charges every index its write cost. Without it every query "needs" an index. `STATE.md` user decisions.
2. Rank partial ownership (an instance owning 1/4 or 1/16 of partitions) above full ownership. Several effects (S8-S5 `keys_strict` 163 ms, `partition_no` index order loss) appear only there; S5/S6/S7 had full ownership only in early rounds. `STATE.md` 2026-11-02, `experiments/R3-screen.md`.
3. Staged runs: screen (P1/P2 on a few labels + write cost, single runs) -> confirm (survivors on all labels, equivalence, S9, S10) -> validate (top 2-3, at least twice, order swapped, container restarted, against the reference). Only the last stage gives a "final win". `STATE.md` 2026-11-02, `experiments/round3-spec.md`, `experiments/U-index-set.md`.
4. Compare variants back to back on one seeded snapshot, rotate variant order, swap order in pass 2. The pass-1-after-gen cell can be colder (`experiments/CMP-main-vs-jdbc-opt-pg.md`).
5. Measure write cost on every DDL change: WAL bytes per write, committed latency, index sizes, S9 churn, concurrency throughput. Single-statement rolled-back write cells were noise; WAL bytes were stable (about 0.5 % between repeats, outliers of 20 %; repeat once). `experiments/E2x-v1-ablation-add-one.md`, `experiments/R3C-confirm.md`.
6. Define noise thresholds from run-to-run spread: HOT/WRITE 30 %, MON/RET/ADMIN 20 %, and more than 0.15 ms absolute (`results/noise-postgres.md`, `STATE.md`). A difference must beat both.
7. Verify result equivalence of every SQL rewrite against the baseline before timing (26 PASS on S2, S5, S7) and watch for silent-wrong queries: the literal `completed_at IS NULL` count that returns zero for COMPLETED (`experiments/E04-partial-record-key.md`).
8. Isolated benchmarks can miss concurrency effects: the `pages` plan flip appeared only in the 60 s S10 loop (`experiments/R3-stage0-pages-loop.md`). Always include a concurrency scenario and capture plans during it, and count slow plans.
9. Separate query rewrites from index changes by add-one and leave-one-out ablations; effects were often non-additive (`keys_strict` 4272 ms with index only, 807 ms with SQL only, 1.25 ms with both). `experiments/E1x-v1-ablation-leave-one-out.md`, `experiments/E2x-v1-ablation-add-one.md`.
10. Read the plan, not only the time: most wins and losses were explained by a changed access path (early stop vs full aggregation, heap fetches, bitmap AND, sort). Report buffers/heap reads too (A3: time similar, buffers +70 %, until S8-S5 showed 253 vs 165 ms). `experiments/R3X-screen-extension.md`, `experiments/R3C-confirm.md`.
11. Do not conclude from single cells: several "regressions" (`upd_retry`, `count_statuses` 28 vs 39 ms bimodal) did not reproduce. `experiments/U-index-set.md`.
12. Keep a ledger of rejected ideas with the reason, and have the user rule on semantic risks (K3).

PostgreSQL-specific (labelled; the MariaDB experiment needs its own equivalents)
- Run `VACUUM (FREEZE, ANALYZE)` before benchmarking; otherwise index-only scans hit the heap and autovacuum fires at random moments (spreads up to 24x). `STATE.md`, `harness/README.md` (Changes). MariaDB analogue (general knowledge, unverified): run `ANALYZE TABLE` and wait for background statistics/purge, or fix them explicitly.
- Check plan mode: the pgjdbc driver server-prepares after 5 executions and PG may switch to a generic plan; compare `plancache.csv` between variants before attributing a difference to an index. Forced custom/generic runs separate the effects (`experiments/R2-plan-modes.md`).
- Build indexes after the load, but the primary key before it, and mind that load order affects index page fill (UUIDv7 PK +24 % size was an artefact). `experiments/R5-uuidv7-pkey.md`.
- Planner statistics assumptions (independence of `status` and `completed_at`; `record_key` vs `status` in `BitmapAnd`) can be wrong by 2.5x and decide plans (`experiments/R5-pages-bitmapand.md`, `experiments/E1x-v1-ablation-leave-one-out.md`).
- Generic-plan-only metrics: count cells of ~22 ms are parallel `Gather` startup (environment dependent). `experiments/R2-plan-modes.md`.

---

## 7. Open points and known accepted costs on PostgreSQL

Accepted costs
- S8-S5 `keys_strict` ~163-165 ms (hot keys, partial ownership, 18 000 candidates probed). Largest P1 cell left. `experiments/R3C-confirm.md`, `experiments/R4-screen.md`.
- `pages` on hot keys 60-70 ms for 18 000-20 000 rows; `P-KK2` sort guarantee costs +9 %/+13 % on S8-S5 vs the reference, below the HOT threshold. `experiments/R5-pages-form.md`, `experiments/P-KK2-pages-without-inner-sort.md`.
- Deprecated metrics module: `count_partition_single` 0.24-0.30 -> 106-109 ms (S2), 200 ms (S4); calculated 37-68 s per scrape fan-out; accepted by the user and not fixed (`experiments/CMP-main-vs-jdbc-opt-pg.md`).
- `COMPLETED` in `record-statuses`: no index; S4 254-321 ms per refresh; once 1.8-2.0 s (`experiments/R5-d2-literal.md`).
- P3: `delete_by_key_status`, `find_failed_before`, `delete_by_status_failed` slower (index-free by design).
- P2 small regressions: S8-S7 `count_partitions` +0.6 ms, `oldest_created` +0.2 ms (`experiments/CMP-main-vs-jdbc-opt-pg.md`).
- Index growth after churn: partial indexes grow with dead entries until vacuum (49-79 MB for B1 in S9 `experiments/E04-partial-record-key.md`), final set 285-321 MB after churn vs 776-846 MB on main.
- Risk by design: the status partial indexes must keep the same predicate as B1, or `BitmapAnd` returns (ADR 0005, `experiments/R5-pages-bitmapand.md`). JPA cannot express partial indexes; production should use the shipped script.
- D3 write cost: +7 MB, +6 % insert WAL on S4 (`experiments/R5-d2-literal.md`).

Open/unexplained
- Why `MIN` keeps generic plans and `ORDER BY ... LIMIT :limit` does not: not verified (literal `LIMIT 1` untested). `experiments/R5-oldest-due.md`.
- Unexplained S2 `pages` 0.90 vs 0.66 ms for D3 (same plan). `experiments/R5-d2-literal.md`.
- `count_statuses` 28 vs 39 ms bimodal cell, S4 write-cell spread (cause open). `experiments/R3C-confirm.md`, `STATE.md` (Open findings).
- 1/16 ownership with sparse due sets (S5/S6/S7) not measured. `experiments/R3-screen.md`.
- No cold-cache runs; S10 uses 4 pollers on one host; S10 throughput is an inter-run range with overlapping bounds. `experiments/U-index-set.md`.
- Mixed v4/v7 id rows not tested. `experiments/R5-uuidv7.md`.
- Planner rule behind the missing `BitmapAnd` with equal predicates is inferred (planner source not read, PG 18.6 only). `experiments/R5-pages-bitmapand.md`.

**Preliminary note from the user (not in `docs/db-sql/`, not yet documented):** a still-running PostgreSQL test indicates `MIN(created_at)` is better than `ORDER BY created_at LIMIT 1` for `oldest_created` on PostgreSQL. The branch will be updated later. The documented data here (+0.2 ms for the final `ORDER BY` form against main's `MIN`, `experiments/CMP-main-vs-jdbc-opt-pg.md`) points the same way. Treat the `ORDER BY ... LIMIT` form of `oldest_created` in section 3.7 as provisional.

---

## 8. Hypotheses a MariaDB experiment might derive (not measured here)

Labelled general knowledge and pure hypotheses; each needs its own measurement.
- Partial indexes do not exist in InnoDB. Candidate substitutes: composite indexes with `completed_at` as a key column so that `completed_at IS NULL` is a short range; or a generated/virtual column indexed only for incomplete rows (NULL for completed rows, since NULLs are still indexed, size is not reduced).
- The size and write savings from excluding COMPLETED rows will not carry over unless an equivalent exists. Test which of the gains remain: SQL rewrites (drop GROUP BY, `<=` range, ORDER BY + LIMIT), covering columns, fewer indexes.
- No `INCLUDE`: covering means extra trailing key columns. Compare size cost against heap/clustered-index lookups (InnoDB secondary entries always carry the primary key `id`, a `VARCHAR(255)` UUID, which inflates every secondary index).
- To my knowledge MariaDB has no `LATERAL` (general knowledge; verify for the tested version). The `unnest` + `LATERAL` workaround targets a plan-cache flip that probably does not exist; test `IN (...)` on `(record_key, created_at, id)` under the S10 concurrency loop first.
- No extended statistics objects, no generic/custom plan duality, no HOT updates, no visibility map. The `VACUUM`, plan-mode and HOT-related lessons do not transfer; the equivalents are `ANALYZE TABLE`, optimizer histograms/`innodb_stats_*`, purge lag and redo volume.
