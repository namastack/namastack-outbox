# Phase 4 — Exploration: what won, what was rejected, evidence

MariaDB 12.3.3, UUIDv4 ids unless stated. Judged by the ADR-0005 rule: an index or form must be justified by P1/P2 benefit
against its write cost; partial ownership ranks first; P3 is reported, not judged. Baselines: `mdb-main` (`f4446c8c`) and the
branch `mdb-opt` (`2a2663de`), phase 3 (`03-main-vs-branch.md`).

Reports:
- round 1a: `experiments/r1a-index-sets.md` (spec `experiments/round1-spec.md`);
- round 1b: `experiments/r1b-forms.md` (spec `experiments/round1b-spec.md`);
- round 2: `experiments/r2-fourth-index.md` (spec `experiments/round2-spec.md`);
- confirm + validate: `experiments/confirm.md` (spec `experiments/confirm-spec.md`).

## 1. Final set

**DDL** (`experiments/r2/spc/ddl.sql`), four secondary indexes on `outbox_record`:

| Index | Key | Serves |
|---|---|---|
| i1 `idx_outbox_record_status_completed_created_id` | `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)` | `keys_strict`, `keys_nonstrict` outer scan, `oldest_created` (ordered, stops at the first owned row) |
| i2 `idx_outbox_record_key_completed_created_id` | `(record_key, completed_at, created_at, id)` | head subqueries of `keys_*`, `pages`, `oldest_due_strict` probe, `find_incomplete_by_key` |
| i3 `idx_outbox_record_status_completed_retry` | `(status, completed_at, next_retry_at, partition_no)` | `oldest_due_strict`, `oldest_due_nonstrict` (ordered) |
| i4 `idx_outbox_record_status_partition_created` | `(status, partition_no, created_at)` | `count_partitions`, `count_partition_single` at partial ownership |

i1-i3 are the branch's B-1a, B-1b, B-1c unchanged. i1 and i3 both start with `(status, completed_at)`, which also serves the retention
select (P3). The branch's B-1d `(status, completed_at, partition_no)` is replaced by i4.

**Queries** (`experiments/r2/forms-a/queries/`), MariaDB overrides; every other statement stays as on the branch:

| Query (repository method) | Final form | Q-merged id |
|---|---|---|
| `keys_strict` (`findStrictRecordKeys`) | head form: `o.id = (SELECT e.id ... WHERE e.record_key = o.record_key AND e.completed_at IS NULL ORDER BY e.created_at, e.id LIMIT 1)`, outer `ORDER BY o.created_at, o.id` | `ks-05-head` |
| `keys_nonstrict` (`findNonStrictRecordKeys`) | head form, subquery also `e.status = :status AND e.next_retry_at <= :now` | `kn-05-head` |
| `oldest_created` (`oldestCreatedAtByPartitionsStatus`) | `SELECT created_at ... ORDER BY created_at, id LIMIT 1` | `oc-03-order-id` |
| `oldest_due_nonstrict` (`oldestDueNonStrict`) | `SELECT o.next_retry_at ... ORDER BY o.next_retry_at LIMIT 1` | `odn-01-order` |

The forms keep the outer `completed_at IS NULL`, so they do not depend on the NEW <=> `completed_at IS NULL` invariant.

## 2. Result of the final set (confirm stage, two passes, `c-1`)

- **Equivalence:** PASS against the branch on S1, S2, S5, S7 (and the round-1b / round-2 checks on S1, S5, S7).
- **Against the branch: win.**
  - The full-ownership collapse is gone: S4/S5/S6 `keys_*` and `oldest_*` over 10 s (branch) -> 0.3-0.85 ms; S7 0.77 s -> 0.3-0.6 ms.
  - S10-S2-p1-unthr 2 -> 422-439 rec/s.
  - Partial ownership: S8-S4 `oldest_created` 163 -> 0.25 ms, `oldest_due_nonstrict` 142 -> 0.38 ms, `count_partitions` 115 -> 29 ms;
    S16-S4 `oldest_*` 5 s -> 0.2 ms, `count_partitions` 100 -> 7.3 ms; S8-S5 `keys_strict` 108 -> 54 ms, `keys_nonstrict` 4.9 -> 0.59 ms.
  - S4-wc 1-thread insert redo 5713 -> 2642 B per write (main 2613).
- **Against main: net win** (mixed only in the cells listed in section 4).
  - S4 `keys_nonstrict` 91 s -> 0.66 ms; S8-S4 `keys_nonstrict` 26 s -> 0.54 ms; S8-S5 `keys_strict` 15 s -> 54 ms.
  - S10: p1-unthr 342-394 -> 422-439 rec/s, p4-unthr 969-981 -> 1323, S10-S4-p4 701-768 -> 903-961, S10-S4-p4-unthr 370-388 -> 535-564.
  - `count_partitions` at partial ownership equal to main (S16-S4 7.3 vs 7.5 ms, S8-S5 4.3 vs 8.7).

## 3. Evidence per decision

**Indexes.**
- **B-1d dropped (root cause R1 of the collapse).** At 256 owned partitions (`eq_range_index_dive_limit` 200) `(status, completed_at,
  partition_no)` gets a statistics estimate of one row per partition, wins the plan and filesorts the whole NEW backlog (round 1a,
  M8: the plan flips at n >= 200). Every set without a `(status, completed_at, partition_no[, x])` prefix fixes it (round 1a).
- **i1, i2, i3 are the minimal set (M7).** `r1-a1` (i1 + i2) loses `oldest_due_strict` (round 1a); no `ods-*` form avoids i3 (round 1b).
- **i4 = `(status, partition_no, created_at)`** (round 2, user decision that `count_partitions` must not stay slow):
  - `(status, partition_no)` costs +144 % S4 insert redo and -14 % S10 with UUIDv4 (random id order inside each group);
    `created_at` makes the inserts per-partition appends (+10-12 % insert redo).
  - Remaining price (confirm): S2-wc completion redo +87 %, delete 2.7x against the branch; insert, retry, fail no difference.
  - The 4th index never wins a plan with a false estimate at 16, 199, 200 or 256 partitions (round 2, measurement 4).
  - It adds 0.03-0.24 ms per statement to `keys_*` and `oldest_*` by **planning**: one index dive per owned partition (64 or 256)
    while estimating its ranges; execution and plans are identical (confirm section 9).
- **M4:** an explicit `id` key part (i2 vs `(record_key, completed_at, created_at)`) changes neither plans nor size (`b-m4`, round 1b).
- **Rejected sets:** `completed_at`-leading (`r1-e1`, `r1-e1b`), partition-leading per-partition MIN (`r1-e2q`, keeps the collapse),
  E4 (`r1-e4`/`r1-e4q`), E5 with `(status, partition_no)` (`r1-e5q`) — same reads as the winner at higher write cost (round 1a).

**Query forms.**
- **`keys_strict` / `keys_nonstrict` head forms (M6).** Branch `NOT EXISTS` runs as a dependent subquery that reads every earlier
  unfinished record of a key; the head form probes i2 for the key's first record (17 rows instead of 52k at S8-S5). S8-S5 0.50x /
  0.11x, S5 0.18x / 0.24x; elsewhere no difference (round 1b). Window, `LEFT JOIN`, `GROUP BY` and tuple forms: no better (round 1b).
  Non-strict risk with K future-scheduled records at a key's head: `kn-05-head` stays below the branch form at K = 10, 100, 1000
  (0.63x / 0.80x at K = 1000; round 2 part B). Strict mode is covered by S5 (a future head blocks the key).
- **`oldest_created` / `oldest_due_nonstrict` ORDER BY ... LIMIT 1 (M2, M3).** MIN cannot stop early because `partition_no` follows
  the MIN column in the index; the ordered form reads in index order and stops at the first owned row: 0.15-0.40 ms at every label,
  below main everywhere, no write cost (round 1b; `r1-e5c` MIN control in round 1a).
- **`oldest_due_strict`:** the branch form (B-7) is kept; no `ods-*` form is better (round 1b).
- **`pages` (M5):** `IN` list vs `JSON_TABLE` / `VALUES` / per-key `UNION ALL`: no difference, the cost is row transfer (round 1b).
- **`count_statuses`, `count_partitions`:** no better form (round 1b); `count_partitions` is fixed by i4.
- **M1 query reverts (`b-m1q`, main SQL on the new DDL):** loss for every query (round 1b). Branch items B-2..B-8 are kept, with the
  overrides above replacing B-2, B-3, B-6 and B-7 (nonstrict part).

**Second candidate `c-2`** (E5 indexes without `completed_at`, forms without the outer `completed_at IS NULL`, + i4): equal to `c-1`
within noise on P1/P2 and S10. Measured differences: index bytes -2-3 %; `oldest_created` +0.1 ms at partial ownership; `count_statuses`
+6-19 %; no `completed_at` index, so retention sorts every COMPLETED row per batch (S9: 26,000 of 500,000 removed in 2700 s, like main).
`c-1` is chosen on the P2 differences and the retention path; the invariant was not used as a tie-breaker (user decision).

## 4. Remaining differences against main (accepted, with reason)

| Cell | main | final | Reason it stays |
|---|---|---|---|
| `count_statuses` (P2), all labels | `count_new` + `count_failed` | +20-45 % | B-5 grouped count: same index scan plus grouping; no better form (round 1b). |
| S16-S2 `keys_nonstrict` (P1) | 0.39 ms | 0.68 ms | 62 NEW rows in the owned partitions: main's grouped scan reads 53 rows once, the head form probes i2 per candidate (116 rows) plus about 0.05 ms planning for i4. A fixed 0.3 ms at a near-empty backlog; main's form takes 26 s at S8-S4 and 91 s at S4. |
| S16-S2 `oldest_due_strict` (P2) | 0.43 ms | 0.49 ms | Same plan shape; the 4th index planning cost. |
| `count_partitions` at full ownership | — | +21-25 % vs branch (S5/S6 22 -> 27 ms) | Main is 13 s on S5. The planner scans the NEW range of i3 instead of B-1d; no cheaper plan without hints. |
| Planning cost of i4 | — | +0.03-0.24 ms on `keys_*`, `oldest_*` | Range dives per owned partition; removable only by hints or `eq_range_index_dive_limit` (user decision, not proposed). |
| S2-wc completion / delete redo | 5.6k / 2.9k B | 8.6k / 5.7k B | i4 entries move on completion and are removed on delete; price of the P2 fix (user decision to keep a 4th index). |

## 5. Stop criterion

Every P1/P2 query has a justified recommendation (sections 1, 3, 4): the stop criterion of the orchestrator prompt is met after the
confirm stage. No round 3. Not pursued:
- forms for the S16-S2 `keys_nonstrict` cell: a fixed sub-ms cost at a near-empty backlog, against seconds saved at every larger
  backlog;
- a cheaper i4 planning cost: every index usable for `count_partitions` gets the same per-partition dives.

## 6. UUIDv7 (round 2, observation)

With `HARNESS_ID_VERSION=7`: PRIMARY -28 %, insert redo -30 %, S10-S2-p1-unthr 2.1x (about 915 rec/s); S2 completion / retry / fail
redo +45-140 % on the reference (cause not isolated). The index ranking does not change. The final comparison uses UUIDv4 (user).

## 7. Items needing a user decision

None in the final set. Not proposed: index hints, generated columns, other schema changes, server settings
(`eq_range_index_dive_limit`).
