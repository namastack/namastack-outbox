# MariaDB study — final report

MariaDB 12.3.3 (`mariadb:12`), InnoDB, 128 MB buffer pool unless stated, UUIDv4 ids, harness in `harness/`. Variants:

| Name | Code | MariaDB design |
|---|---|---|
| `main` | `f4446c8c` | 5 secondary indexes, original SQL |
| branch (`mdb-opt`) | `2a2663de` | branch items B-1..B-9 (`STATE.md`, "Branch change list") |
| branch, moved (`mdb-opt2`) | `4037faa5` = current `origin/feature/db-jdbc-optimization` | status indexes without `completed_at`, 4th index `(status, partition_no)`, statements without `completed_at IS NULL` |
| **final** (`mdb-final`) | `exp/mdb-final` `f31bc505` | section 4 |

Judged by the ADR-0005 rule: an index or statement form must be justified by P1/P2 read benefit against its write cost; partial
ownership first; P3 (monitoring counts, retention) is reported, not judged. Evidence: `03-main-vs-branch.md` (phase 3),
`04-exploration.md` (phase 4 and its round reports), `results/final/SUMMARY.md` (phase 6), `experiments/m5-s10.md`,
`experiments/opt2-vs-final.md`.

## 1. Executive summary

**Recommendation: apply the final design to MariaDB.** It is the branch at `2a2663de` with the 4th index replaced and five statements
reshaped; against the current branch head (`4037faa5`) it puts `completed_at` back into the status indexes and the statements.

- **Against `main` (phase 6, two passes): net win.**
  - P1 at partial ownership: `keys_strict` -21 to -30 % (S8-S2, S8-S4), S8-S5 13.3 s -> 54 ms; `keys_nonstrict` S8-S4 21 s -> 0.54 ms,
    S16-S4 5.1 s -> 0.46 ms, S8-S5 2.2 s -> 0.60 ms; `pages` S8-S5 4.0 s -> 1.27 s. Full ownership: S4 `keys_nonstrict` 85 s -> 0.68 ms.
  - S10 throughput: S10-S2-p4-unthr 935-1063 -> 1296-1309 rec/s, S10-S4-p4 769-784 -> 888-918, S10-S4-p4-unthr 393-405 -> 566-579;
    throttled S2 cells equal (producer-bound).
  - P2: `oldest_created`, `oldest_due_strict` from seconds (S8-S4 14-19 s, S16-S4 4.9-7.3 s) to 0.2-0.4 ms everywhere; `count_partitions`
    equal at partial ownership (S16-S4 7.3 vs 7.4 ms, S8-S5 4.3 vs 4.3 ms), S5 13.5 s -> 27 ms.
  - Costs: `count_statuses` +24-41 % (P3; one grouped statement against main's two counts); S16-S2 `keys_nonstrict` 0.40 -> 0.67 ms
    (62 NEW rows; fixed sub-ms cost); write cost below.
- **Against the branch at `2a2663de`: win.** Removes the full-ownership collapse (S4/S5/S6 `keys_*`/`oldest_*` over 10 s -> under 1 ms;
  S7 0.77 s -> 0.6 ms; S10-S2-p1-unthr 2.3 -> 402-465 rec/s) and the partial-ownership P2 slowness (S8-S4 `oldest_created` 161 -> 0.26 ms,
  `count_partitions` 107 -> 29 ms, S16-S4 `oldest_*` 5-6 s -> 0.2 ms); S4-wc insert redo 5.7 -> 2.6 KB/write.
- **Against the current branch head `4037faa5`: win (one pass).** P1/P2 reads equal within noise on every label. Write cost lower:
  S4-wc 1-thread insert redo 5672 -> 2647 B/write (-53 %), 4-thread -24 %; S2-wc completion -20 %, retry -14 %, delete -25 %. Retention
  (P3, but operationally decisive): 4037faa5 has no `(status, completed_at)` index, the retention select sorts all COMPLETED rows
  (about 2 min per 1,000-row batch) and removed 18,000 of 500,000 rows in 2700 s; final removed all 500,000 in 358 s. Index bytes +3-4 %.
- **Write cost and size against `main`:** inserts equal (S2-wc/S4-wc within noise); S2-wc completion redo +60 %, delete +103 %, retry
  +15 %; S4-wc retry +293 % (the same on the branch: two status indexes carry `next_retry_at`). Index bytes 7-9 % smaller than main
  (S2 347 vs 374 MB, S4 642 vs 704 MB), one index fewer.
- **2 GB buffer pool:** every conclusion holds (`results/final/SUMMARY.md` section 7).

## 2. Recommended SQL per P1/P2 query

Index names: i1 `idx_outbox_record_status_completed_created_id`, i2 `idx_outbox_record_key_completed_created_id`, i3
`idx_outbox_record_status_completed_retry`, i4 `idx_outbox_record_status_partition_created`.

| Query (repository method) | Final MariaDB SQL | Index | Rejected forms (evidence) |
|---|---|---|---|
| `keys_strict` (`findStrictRecordKeys`) | `... WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND o.id = (SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL ORDER BY e.created_at, e.id LIMIT 1) ORDER BY o.created_at, o.id LIMIT :limit` | i1 outer scan in `(created_at, id)` order, i2 one probe per candidate | main `GROUP BY`; branch `NOT EXISTS` (dependent subquery reads every earlier unfinished record: S8-S5 107 vs 54 ms, S5 > 10 s); window, `LEFT JOIN`, tuple forms (round 1b) |
| `keys_nonstrict` (`findNonStrictRecordKeys`) | same, subquery adds `AND e.status = :status AND e.next_retry_at <= :now` | i1, i2 | main `GROUP BY ... MIN(created_at)` (S4 85 s); branch `NOT EXISTS` (S8-S5 4.8 vs 0.6 ms); checked with up to 1,000 future-scheduled records at a key's head (round 2 part B) |
| `pages` | branch `IN (:recordKeys) AND completed_at IS NULL` (unchanged) | i2 | `JSON_TABLE`, `VALUES` CTE, per-key `UNION ALL`, semi-join: no gain alone (round 1b) or under S10 (`experiments/m5-s10.md`: equal throughput, `pages` p50 +0.1-0.16 ms) |
| `oldest_created` (`oldestCreatedAtByPartitionsStatus`) | `SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at, id LIMIT :limit` (`limit` 1, `setMaxRows(1)`) | i1 in order, stops at the first owned row | `MIN(created_at)` (cannot stop early: `partition_no` follows `created_at` in the index; S8-S4 161 ms, S16-S4 5 s); without `completed_at` (+0.1 ms, c-2) |
| `oldest_due_strict` | branch form: `... AND o.completed_at IS NULL AND NOT EXISTS (earlier incomplete record) ORDER BY o.next_retry_at LIMIT :limit` | i3, i2 probe | head forms and others (`ods-*`, round 1b): none better |
| `oldest_due_nonstrict` (`oldestDueNonStrict`) | `SELECT o.next_retry_at ... AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT :limit` | i3 in order | `MIN` (S8-S4 142 ms, S16-S4 5.3 s on the branch) |
| `count_partitions`, `count_partition_single` | branch SQL (unchanged) | i4 | without i4: S16-S4 106 ms, S8-S4 114 ms (round 2) |
| `count_statuses` | branch B-5 (unchanged) | i1 | no better form (round 1b) |

All final statements passed equivalence against `main` and the branch on S1, S2, S5, S7 (phase 6; one WARN: S5 `keys_nonstrict` vs
`main`, a `created_at` tie order, equal key sets).

## 3. Keep / drop / modify

| Item | Decision | Reason |
|---|---|---|
| B-1a i1 | keep | serves `keys_*`, `oldest_created`, `count_statuses`; retention prefix |
| B-1b i2 | keep | head subqueries, `pages`, `find_incomplete_by_key` (an explicit `id` key part changes nothing: M4) |
| B-1c i3 | keep | `oldest_due_*`; without it `oldest_due_strict` loses (round 1a) |
| B-1d `(status, completed_at, partition_no)` | **drop** | root cause of the 256-partition collapse: above `eq_range_index_dive_limit` (200) its estimate is one row per partition, it wins the plan and filesorts the NEW backlog (round 1a, M8) |
| new i4 `(status, partition_no, created_at)` | **add** | `count_partitions` back to main's level at partial ownership; `created_at` makes inserts per-partition appends (`(status, partition_no)`: +144 % S4 insert redo, -14 % S10 with UUIDv4; round 2, and `opt2-vs-final.md`); never wins a plan with a false estimate at 16/199/200/256 partitions; cost: 0.03-0.24 ms planning per `keys_*`/`oldest_*` statement (one dive per owned partition) |
| B-2 `keys_strict` `NOT EXISTS` | **modify** -> head form | section 2 |
| B-3 `keys_nonstrict` | **modify** -> head form | section 2 |
| B-4 `pages`/`find_incomplete_by_key` `completed_at IS NULL` | keep | `main` form 3-3.5x slower at hot keys (round 1b) |
| B-5 grouped `count_statuses` | keep | P3; no better form |
| B-6 `oldest_created` | **modify** -> `ORDER BY created_at, id LIMIT 1` | section 2 |
| B-7 `oldest_due_strict` / `oldest_due_nonstrict` | keep strict; **modify** nonstrict -> `ORDER BY ... LIMIT 1` | section 2 |
| B-8 `LIMIT` on retention selects, `setMaxRows` | keep | |
| B-9 dialect machinery | keep | needed for the MariaDB overrides |
| `4037faa5` changes (no `completed_at` in status indexes and statements, `(status, partition_no)`) | **do not use for MariaDB** | retention without index; higher insert/completion/delete redo; reads equal (`opt2-vs-final.md`) |

## 4. Final DDL, write cost, storage, buffer pool

```sql
INDEX idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key),
INDEX idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id),
INDEX idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no),
INDEX idx_outbox_record_status_partition_created (status, partition_no, created_at)
```

Write cost, redo bytes per committed write (phase 6, mean of two passes; noise 12 %):

| Phase | S2-wc main / branch / final | S4-wc main / branch / final |
|---|---|---|
| insert 1 thread | 2483 / 2319 / 2351 | 2575 / 5705 / 2589 |
| completion | 5699 / 4948 / 9129 | 14327 / 14570 / 14494 |
| retry | 2794 / 2945 / 3203 | 3632 / 14276 / 14285 |
| fail | 4589 / 4124 / 3998 | 3897 / 4162 / 4132 |
| delete | 2794 / 1915 / 5685 | 1667 / 1824 / 2097 |

Latencies p50/p95 are within noise of main except S4-wc retry p50 +17 %. The completion/delete price is i4 (its entry moves on
completion, is removed on delete); the retry price on S4 is i1/i3 carrying `next_retry_at` (as on the branch). Index bytes at load:
final 347 MB (S2) / 642 MB (S4), main 374 / 704, `4037faa5` 332 / 622. S9 churn: final removes 500,000 rows in 294-358 s, main 31,000 in
the 2700 s cap.

**Buffer pool 2 GB** (S8-S2, S8-S4, S8-S5, S2-wc, S10-S2-p4; main vs final): all conclusions hold. Main is faster with a warm pool
(S8-S5 `keys_strict` 331 ms instead of 13 s, `pages` 267 ms) but final stays ahead (53 ms, 51 ms); `count_statuses` +25-36 % and the
write-cost deltas are unchanged.

## 5. Risks, semantics, portability, open items

- **Semantics.** Equivalence PASS for every final statement against main and the branch (S1, S2, S5, S7) and against `4037faa5` (S1, S5,
  S7). The statements keep the outer `completed_at IS NULL`, so they do not depend on "NEW exactly when `completed_at` is NULL" (not used
  as a reason; the user accepts that rule). Strict ordering: a key whose oldest unfinished record is FAILED or not due is blocked (S5).
- **Planner risks.** The final plans rely on index order plus row limit; with 256 owned partitions the range estimate uses statistics
  (dive limit 200), and i4 was checked not to win with a false estimate (16/199/200/256 partitions). `main` showed an unexplained plan
  flip for `oldest_due_nonstrict` on S4 (75-82 s vs 411 ms across restarts); the final statements did not flip in any run.
- **S16-S2 `keys_nonstrict`** 0.40 -> 0.67 ms against main (accepted: fixed sub-ms cost at a near-empty backlog).
- **Dialect detection.** A MariaDB server reached through MySQL Connector/J or with `useMysqlMetadata=true` reports `MySQL`, so it gets
  `MySqlJdbcOutboxRecordQueries` and `schema/mysql`. At `4037faa5` the MySQL and MariaDB classes and DDL are identical, so this is
  harmless there; with the final changes such a deployment runs the MySQL design (no retention index on MariaDB). Document "use the
  MariaDB driver" or detect MariaDB from the server version string — user decision.
- **JPA entity.** `OutboxRecordEntity` declares the indexes independent of the dialect (at `4037faa5`: the `4037faa5` set). Hibernate-
  generated DDL on MariaDB therefore gets the `4037faa5` indexes, not the final ones; JPA statements are JPQL and were not studied.
  Recommend the shipped schema script for MariaDB; changing the entity is a user decision (it affects every dialect).
- **MySQL portability.** MySQL is not changed. The final MariaDB forms are plain SQL and run on MySQL; whether the MariaDB results carry
  over is the subject of the follow-up MySQL experiment (separate report).
- **UUIDv7 (observation, round 2).** With time-ordered ids: PRIMARY -28 %, insert redo -30 %, S10-S2-p1-unthr 2.1x; S2 completion/retry/
  fail redo +45-140 % (cause not isolated). The index ranking does not change. The library generates UUIDv4 today.
- **Host.** The host is shared (outside load 11-13 at times); one-pass results (`opt2-vs-final.md`, `m5-s10.md`) count only where the
  difference is far above noise.
- **Items waiting for a user decision:** dialect detection (above), JPA entity indexes (above). Not proposed: index hints, generated
  columns, other schema changes, server settings (`eq_range_index_dive_limit`).

**Minimal test list for the quiet machine** (all optional; none changes the recommendation):
1. Phase-6 subset main vs final, two passes: S8-S2, S16-S2 (`keys_nonstrict`, `oldest_due_strict` sub-ms deltas), S10-S2-p1-unthr and
   S10-S2-p4-unthr (throughput, the noisiest cells here).
2. `4037faa5` vs final, two passes: S4-wc and S2-wc (insert/completion redo), S10-S2-p1-unthr, S10-S4-p4.
3. S9 with a longer retention budget for main / `4037faa5` (how far behind retention falls).
4. S4-wc retry redo with a realistic retry mix (+293 % against main on both branch designs).

Pages forms (M5), the c-1 vs c-2 choice and the 4th index were closed here; no other deferred test remains open.

## 6. `exp/mdb-final` and the changes for `feature/db-jdbc-optimization`

`exp/mdb-final` (worktree `../outbox-wt-mdb-final`, base `2a2663de`, commits `76c51fdb`, `f31bc505`): MariaDB DDL (B-1d -> i4),
`MariaDbJdbcOutboxRecordQueries` overrides for `findStrictRecordKeys`, `findNonStrictRecordKeys`, `oldestCreatedAtByPartitionsStatus`,
`oldestDueNonStrict`; `JdbcOutboxRecordRepository.findOldestCreatedAtByPartitions` binds `limit` 1 and `setMaxRows(1)`; new
`MariaDbJdbcOutboxRecordQueriesTest`; MariaDB schema integration test asserts the index list; `database.md`, `upgrading-to-1-12.md`.
Tests: `:namastack-outbox-jdbc:test` 537/0 failures, `*MariaDb*` integration 2/0, ktlint OK (`05-final-candidate.md`).

**Changes for `feature/db-jdbc-optimization` at `4037faa5`** (applied as uncommitted changes in `../outbox-wt-jdbc-opt`, see below):
1. `schema/mariadb/outbox-tables.sql`: the four indexes of section 4 (replacing `status_created_id`, `status_retry_partition`,
   `status_partition`).
2. `MariaDbJdbcOutboxRecordQueries`: `findStrictRecordKeys`, `findNonStrictRecordKeys` with the outer `completed_at IS NULL`;
   overrides `oldestCreatedAtByPartitionsStatus`, `oldestDueStrict`, `oldestDueNonStrict` with `completed_at IS NULL` (section 2).
3. Tests and the MariaDB parts of `database.md` / `upgrading-to-1-12.md`.
The repository change (`limit` 1, `setMaxRows(1)`) is already on the branch. MySQL, other dialects and the JPA entity unchanged.

**Applied (orchestrator decision; user delegated it):** worktree `/home/azamojski/outbox-wt-jdbc-opt`, branch `feature/db-jdbc-optimization`
at `4037faa5`, uncommitted:

```
 M namastack-outbox-docs/docs/reference/database.md
 M namastack-outbox-docs/docs/reference/upgrading-to-1-12.md
 M namastack-outbox-jdbc/src/main/kotlin/io/namastack/outbox/query/MariaDbJdbcOutboxRecordQueries.kt
 M namastack-outbox-jdbc/src/main/resources/schema/mariadb/outbox-tables.sql
 M namastack-outbox-jdbc/src/test/kotlin/io/namastack/outbox/schema/JdbcSchemaInitializationMariaDbIntegrationTest.kt
?? namastack-outbox-jdbc/src/test/kotlin/io/namastack/outbox/query/MariaDbJdbcOutboxRecordQueriesTest.kt
```

- Tests (JDK 25 init script): `:namastack-outbox-jdbc:test` 538 / 0 failures; `:namastack-outbox-integration-tests:test --tests "*MariaDb*"`
  2 / 0; `ktlintCheck` clean.
- Docs: `upgrading-to-1-12.md` has separate MySQL and MariaDB sections (MariaDB: create the four indexes, drop the pre-1.12 ones and any
  1.12 snapshot index not in the list); `database.md` describes the MariaDB index shape and that it serves retention.
- Not changed: examples (no MariaDB schema file), JPA entity, MySQL and the other dialects.
- Open for the user: JPA on MariaDB selects incomplete records by status alone; with the shipped MariaDB schema its statements do not
  match the `completed_at`-second indexes (not measured; JPQL was out of scope).
