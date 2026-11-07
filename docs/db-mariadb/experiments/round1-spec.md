# Round 1 spec — screen (final, 2026-11-06)

Orchestrator spec for round 1 of phase 4. Stage: **screen** (one pass) unless stated. Base: `mdb-opt` (`2a2663de`, branch DDL and SQL).
Brief: `briefs/06-experiment.md`. Noise thresholds: `STATE.md` (HOT 11 %, MON 10 % / 0.04 ms, WRITE 60 % and >= 0.2 ms; writecost redo 12 %,
p50 8 %, p95 25 %). Host-load rule: `STATE.md` user decision of 2026-11-05. Phase 3 findings and problem cells: `03-main-vs-branch.md`
(sections 3 and 5).

Round 1 is split. **Round 1a** (this section) removes the full-ownership collapse (R1/R2 in `03-main-vs-branch.md`: B-1d
`(status, completed_at, partition_no)` chosen with a false 256-row estimate when 256 partitions exceed `eq_range_index_dive_limit`)
and screens the index-set ideas. **Round 1b** (parts A-C below) runs the query-form packs and M5 on the round-1a winner DDL, because on
the branch DDL every full-ownership result is dominated by R1.

## Round 1a (run now)

### Labels

Screen labels: `S8-S4`, `S8-S5`, `S8-S7`, `S16-S4` (partial ownership; `S8-S2` dropped: all branch values there are < 1 ms and within
reach of every candidate) and `S4`, `S5`, `S6`, `S7` (full ownership, the problem cells). Plus, for every variant, `S2-wc` and `S4-wc`
(writecost) and `S10-S2-p1-unthr` (concurrency, 1 poller, rate 0). Per-query limit on the full-ownership labels and on S4-*: **10 s**
(screen only; the targets are main's millisecond values, so anything over 10 s is a loss either way); elsewhere max(2 x `mdb-opt`
median, 60 s). `pages` on S4: if `keys_strict` stops at the limit, run `pages` with the S4 keys returned by `mdb-main`'s
`keys_strict` (or the first 10 keys of `created_at` order) and say so; it must not be reported as "stopped".

### Variants (branch SQL unless a pack is named; DDL files `experiments/r1/<variant>/ddl.sql`)

| Run | DDL | Queries | Why |
|---|---|---|---|
| ref | branch (`mdb-opt`) | branch | reference, first and last in each label block |
| `r1-m1d` | i1, i2, i3 (drop B-1d) | branch | M1 for B-1d; R1 fix by removal |
| `r1-e3` | i1, i2, i3, `(status, partition_no)` | branch | R1 fix by replacement (user idea); `count_partitions` (R3) |
| `r1-a1` | i1, i2 | branch | M7 minimal; R1 and B-1c together |
| `r1-e1b` | `(completed_at, status, created_at, id, partition_no, next_retry_at, record_key)`, `(completed_at, record_key, created_at, id)`, `(completed_at, status, next_retry_at, partition_no)`, `(status, partition_no)` | branch | user idea |
| `r1-e4` | `(status, created_at, id, partition_no, next_retry_at, record_key, completed_at)`, i2, `(status, next_retry_at, partition_no, completed_at)`, `(status, partition_no)` | branch | user idea, index effect alone |
| `r1-e4q` | as `r1-e4` | pack `r1-qf13` | user idea with its query forms (R4: ORDER BY ... LIMIT 1) |
| `r1-e5q` | `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2, `(status, next_retry_at, partition_no)`, `(status, partition_no)` | pack `r1-qf14` | user idea |
| `r1-e5c` | as `r1-e5q` | pack `r1-qf15` | E5 controls (MIN forms, `kn-13`); bench labels only, no writecost/S10 (same DDL as `r1-e5q`) |
| `r1-e2q` | i1, i2, `(status, completed_at, partition_no, next_retry_at)`, `(status, completed_at, partition_no, created_at)` | pack `r1-qf12` | R3/R4: per-partition loose index scan |

For `r1-e5c` and `r1-e4q`/`r1-e4` (same DDL), prepare once per label and run the query variants back to back.

Also in round 1a, **M8 (plans only, no timings)** on S5 and S7 data with the branch DDL and with `r1-m1d`: `ANALYZE FORMAT=JSON` of
`keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_*`, `count_partitions` with 16, 64, 199, 200, 201, 256 owned partitions,
and with session `eq_range_index_dive_limit` = 0 / 200 / 300 at 256 partitions: chosen index, `rows` vs `r_rows`. Expected: B-1d's
estimate drops to ~1 row per partition from 201 partitions upwards.

Report: `experiments/r1a-index-sets.md` (brief 06 format, one section per run, partial ownership first, then full ownership and
S10-S2-p1-unthr, then write cost; M8 as its own section). Verdict per run against the ref and against `mdb-main` (phase 3 values in
`results/cmp/SUMMARY.md`): the target at full ownership is main's level.

## Labels (round 1b)

Screen labels (`02-scenarios.md`): `S8-S2`, `S8-S4`, `S8-S5`, `S8-S7`; plus `S2-wc` for every DDL variant; plus the full-ownership
problem cells `S5`, `S7`, `S4` (10 s limit) and `S10-S2-p1-unthr` for P1 forms. Base DDL: the round-1a winner instead of the branch DDL.

## Execution rules

- All timed steps serial in lane 1, lane 2 idle during timed steps (calibration decision).
- Work per snapshot (gen once per scenario): for each label run the `mdb-opt` reference **first and again last** in the label block
  (drift check under the outside load). A variant is compared with the mean of both references; if the two references differ by more
  than the group threshold, the block is flagged.
- Query-only variants (part A) share the `mdb-opt` DDL: prepare the run schema once per label, then run the packs without `--prepare`
  (bench rolls back writes) and with `--only <overlaid ids>`; re-prepare if a pack fails mid-write. Each overlay dir is
  `experiments/r1/<variant>/queries/<query id>.sql` (copy the merged form, file renamed to the query id), used with `--queries` and
  `--catalog-variant mdb-opt`.
- DDL variants (part B) use `--ddl experiments/r1/<variant>/ddl.sql` with the branch SQL, `--prepare` per label, and `writecost` on `S2-wc`.
- Per-query limit: max(2 x `mdb-opt` median of the same query and label, 60 s).
- Screening needs no equivalence; every survivor gets equivalence (S1 + S5 or S7) before confirm.

## Part A — query forms (packs on the branch DDL)

Forms of different queries are timed independently, so one variant ("pack") carries one form per query. Sources:
`experiments/Q-merged/<query>/<form>.sql`.

| Pack | keys_strict | keys_nonstrict | pages | count_statuses | count_partitions | oldest_created | oldest_due_strict | oldest_due_nonstrict |
|---|---|---|---|---|---|---|---|---|
| `r1-qf1` | ks-01-split | kn-01-split | pa-01-latejoin | cs-02-split-null-sum | cp-02-split-null | oc-06-order-limit (M2) | ods-01-min (M3) | odn-01-order (M3) |
| `r1-qf2` | ks-02-tuple | kn-02-tuple | pa-02-semi-in | cs-03-split-null-count | cp-04-incomplete (s-c) | oc-02-min-of-limit | ods-02-split | odn-02-min-of-limit |
| `r1-qf3` | ks-03-leftjoin | kn-03-leftjoin | pa-05-setstmt-nomerge | cs-06-incomplete (s-c) | cp-06-group-sum (s-c) | oc-03-order-id | ods-03-split-min | — |
| `r1-qf4` | ks-04-leftjoin-straight | kn-04-leftjoin-straight | pa-03-force-key (hint) | cs-01-use-idx4 (hint) | cp-01-use-idx4 (hint) | oc-04-order-force (hint) | ods-04-leftjoin | odn-03-order-force (hint) |
| `r1-qf5` | ks-05-head | kn-05-head | pa-04-ignore-status (hint) | cs-07-incomplete-force | cp-05-incomplete-force | oc-05-min-force (hint) | ods-05-leftjoin-straight | odn-04-min-force (hint) |
| `r1-qf6` | ks-06-window | kn-06-window | — | cs-08-incomplete-ignore-wide | cp-03-split-null-idx4 (hint) | — | ods-06-head | — |
| `r1-qf7` | ks-07-window-part | kn-07-window-part | — | — | — | — | ods-07-window | — |
| `r1-qf8` | ks-08-force-outer (hint) | kn-08-groupby | — | — | — | — | ods-08-min-of-limit | — |
| `r1-qf9` | ks-09-force-both (hint) | kn-09-groupby-minid | — | — | — | — | — | — |
| `r1-qf10` | — | kn-10-force-outer (hint) | — | — | — | — | — | — |
| `r1-qf11` | — | kn-11-force-both (hint) | — | — | — | — | — | — |

(s-c) = status-conditional: valid only when the code selects it for NEW/FAILED; measured as such.
Mandatory experiments covered: **M2** (`oc-00-base` MIN vs `oc-06-order-limit`, both `completed_at IS NULL`), **M3** (`ods-00-base`
ORDER BY vs `ods-01-min`; `odn-00-base` MIN vs `odn-01-order`), **M6** (all `ks-*`/`kn-*`; report `S8-S5` and `S8-S7` first).
Code-class forms (`pa-06..09`, `cs-04/05`) need a harness binding extension: see part C.

## Part B — index sets (branch SQL)

DDL sources: `experiments/I-A/*.ddl.sql`, `experiments/I-B/*.ddl.sql`; M1/M4 DDLs are written by the experiment agent from the branch
DDL. Index names: i1 = `idx_outbox_record_status_completed_created_id`, i2 = `idx_outbox_record_key_completed_created_id`,
i3 = `idx_outbox_record_status_completed_retry`, i4 = `idx_outbox_record_status_completed_partition`.

| Variant | Indexes | Experiment |
|---|---|---|
| `r1-m1a` | i2, i3, i4 | M1: drop B-1a |
| `r1-m1b` | i1, i3, i4 | M1: drop B-1b |
| `r1-m1c` | i1, i2, i4 (= `I-B/B3`) | M1: drop B-1c |
| `r1-m1d` | i1, i2, i3 (= `I-A/A3`, `I-B/B2`) | M1: drop B-1d; M7 candidate |
| `r1-m4` | branch, i2 written as `(record_key, completed_at, created_at)` | M4: implicit PK suffix (expect identical size; check plans) |
| `r1-a1` | i1, i2 (= `I-A/A1`, `I-B/B1`) | M7 minimal |
| `r1-a2` | `(status, completed_at, created_at)`, `(record_key, completed_at, created_at)` (`I-A/A2`) | M4/M7: implicit id, non-covering |
| `r1-a4` | d1, i2 (`I-A/A4`) | retry-leading |
| `r1-a5` | i1, i2, d1 (`I-A/A5`) | |
| `r1-b4` | i2, IP (`I-B/B4`) | partition-leading |
| `r1-b5` | i1, i2, IP (`I-B/B5`) | both access paths |

| `r1-e1` | `(completed_at, status, created_at, id, partition_no, next_retry_at, record_key)`, `(completed_at, record_key, created_at, id)`, `(completed_at, status, next_retry_at, partition_no)`, `(completed_at, status, partition_no)` | E1 (user idea): `completed_at` leading. Expected: same seeks for P1 (equality prefix order irrelevant), full index scan for `count_statuses` (no `completed_at` predicate). Measure to confirm. |
| `r1-e1b` | `(completed_at, status, created_at, id, partition_no, next_retry_at, record_key)`, `(completed_at, record_key, created_at, id)`, `(completed_at, status, next_retry_at, partition_no)`, `(status, partition_no)` | E1b (user idea): E1 with the 4th index as `(status, partition_no)` so `count_statuses` keeps a status-leading index. P3 regressions accepted (report only). |
| `r1-e3` | i1, i2, i3, `(status, partition_no)` (i4 replaced) | E3: isolates the `(status, partition_no)` swap on the branch set. Expected: `count_partitions` exact multi-range seek with the branch SQL (no status-conditional form), `count_statuses` unchanged, same write cost as i4. |
| `r1-e4` | `(status, created_at, id, partition_no, next_retry_at, record_key, completed_at)`, i2, `(status, next_retry_at, partition_no, completed_at)`, `(status, partition_no)` | E4 (user idea): `completed_at` moved behind the ORDER BY / range columns (it is redundant for NEW/FAILED: `completed_at IS NULL` holds exactly for them; it stays in the index as a covered filter). Run twice: with the branch SQL, and with pack `r1-qf13` (index and query effect separated). Expected: `keys_*`, `oldest_created` and `oldest_due_nonstrict` read in index order and stop after the first match; P3 retention on COMPLETED loses its `completed_at` range (accepted, report only). |
| `r1-e5` | `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2, `(status, next_retry_at, partition_no)`, `(status, partition_no)` | E5 (user idea): no `completed_at` in the status indexes; queries without the outer `completed_at IS NULL` (packs `r1-qf14`/`r1-qf15`). Run with both packs, not with the branch SQL alone (the branch SQL is valid here but tests E4's question). Expected: P1/P2 as E4, smaller i1/i3; P3 on COMPLETED loses `completed_at` range (accepted). Equivalence on S1/S5/S7 required before confirm (relies on the NEW => `completed_at IS NULL` invariant). |
| `r1-e2` | i1, i2, `(status, completed_at, partition_no, next_retry_at)`, `(status, completed_at, partition_no, created_at)` | E2: `partition_no` before the range/MIN column for P2 (with part A pack `r1-qf12`) |

Pack `r1-qf12` (E2 query forms, run on `r1-e2` DDL and on the branch DDL): `oldest_due_nonstrict` = `SELECT MIN(m) FROM (SELECT
MIN(next_retry_at) m FROM outbox_record WHERE status = :status AND completed_at IS NULL AND partition_no IN (:partitions) AND
next_retry_at <= :now GROUP BY partition_no) t`; `oldest_created` = same shape with `MIN(created_at)` and no `next_retry_at` condition
(loose index scan per owned partition expected: "Using index for group-by"); `count_partitions` = `cp-04-incomplete` (status-conditional).
Expected to fix the `S16-S2` P2 regressions seen in phase 3 pass 1 (`count_partitions`, `oldest_due_nonstrict`).

Pack `r1-qf13` (E4 query forms, user idea; run on `r1-e4` DDL and on the branch DDL): `oldest_created` = `Q-merged/oldest_created/oc-03-order-id`
(`... AND completed_at IS NULL ORDER BY created_at, id LIMIT 1`), `oldest_due_nonstrict` = `Q-merged/oldest_due_nonstrict/odn-01-order`
(`... AND next_retry_at <= :now AND completed_at IS NULL ORDER BY next_retry_at LIMIT 1`). Both are existing forms (`maxrows: one`, LIMIT
bound to 1). Watch at partial ownership: rows examined until the first owned partition matches (about 1/ownership share of the index range).

Packs `r1-qf14` / `r1-qf15` (E5 query forms, user idea; files in `Q-merged`, INDEX section "User idea E5"; run on `r1-e5` DDL, and
`r1-qf14` also on the branch DDL as a control). `r1-qf14`: `ks-10-no-outer-completed`, `kn-12-no-outer-completed`, `oc-08-no-completed-order`,
`ods-09-no-outer-completed`, `odn-06-no-completed-order`. `r1-qf15`: `kn-13-no-completed` (inner condition dropped too, control),
`oc-07-no-completed-min`, `odn-05-no-completed-min` (MIN forms, expected to scan the whole status/due range).

M1 query reverts (B-2 .. B-7) are one pack on the branch DDL, `r1-m1q`: `ks-00-main`, `kn-00-main`, `pa-00-main`, `oc-00-main`,
`ods-00-main`, `odn-00-main` (each overlay is timed independently, so one pack measures every revert alone). B-5 (`count_statuses`)
is judged from phase 3 (`count_statuses` vs `count_new` + `count_failed`).

## Part C — M5 and M8 (after A and B)

- **M5 `pages`:** forms `pa-00-base` (IN on the key index), `pa-01-latejoin`, and the code-class derived key tables `pa-06-json-table`,
  `pa-08-values-cte`, `pa-09-union-per-key`. The harness may be extended minimally to bind them: `:recordKeysJson` (JSON array string of
  the keys), a `(?),(?)` row expansion, and a per-key UNION ALL expansion; document the extension in the harness README. Labels `S8-S2`,
  `S8-S5`, `S4-b100`; S10 for the best form in round 2.
- **M8 `eq_range_index_dive_limit` (observation only, plans, no timings):** on S2 and S4 data, `ANALYZE FORMAT=JSON` of `keys_strict`,
  `keys_nonstrict`, `count_partitions`, `oldest_created`, `oldest_due_*` with 16, 64, 199, 200, 201, 256 owned partitions, and with session
  `eq_range_index_dive_limit` = 0 / 200 / 300 at 256 partitions: chosen index, `rows` estimate vs `r_rows`, plan changes.

## Report

One report per part: `experiments/r1-A-query-forms.md`, `r1-B-index-sets.md`, `r1-C-m5-m8.md` (brief 06 format; partial ownership
first; deferred-test candidates listed explicitly).
