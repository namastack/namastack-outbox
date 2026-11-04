# Phase 5 — round 3 spec (PostgreSQL) — user's P1/P2 index set

Source: user ideas 2026-11-02, round 2 findings (`STATE.md` "Open findings after round 2", `R2-plan-modes.md` section 7). Mechanics as in rounds 1–2,
label suffix `-R3`. No code change. No harness source edits while a driver runs (`bin/bench` rebuilds on newer sources).

## Stages and drivers

Each stage has its own driver and is reviewed by the orchestrator before the next one is defined:
- **Stage 0, `pages` in the loop** and **stage 1, screen** (this file, driver `bin/round3-screen-pg.sh`).
- **Stage 2, confirm** and **stage 3, validate**: survivors chosen after the screen review.

## Stage 0: `pages` form in the processing loop

Round 2: S10 `pages` p50 with `IN` = 11–21 ms in 11 of 16 runs, `unnest` = 0.6–1.3 ms in 8 of 8; isolated bench shows nothing. Hypothesis: generic parallel plan in the long-running loop (unverified).

Forms, all on DDL v1b, S10 loop (`keys_strict` → `pages` → `upd_complete`, pollers 4, rate 0, 60 s, disjoint partitions):
| Form | SQL |
|---|---|
| `PF-UN` | v1 `unnest` + `CROSS JOIN LATERAL` |
| `PF-IN` | `IN (...)` + `ORDER BY record_key, completed_at, created_at, id` (E1P) |
| `PF-NS` | `IN (...)`, no `ORDER BY`, client sort in the loop (U1) |
| `PF-ANY` | `record_key = ANY(CAST(ARRAY[:recordKeys] AS varchar[]))`, no `ORDER BY`, client sort |

Each form × (default, `--pg-no-parallel` = `max_parallel_workers_per_gather = 0`), **4 passes** per combination, order rotated, `--prepare` before each.
The no-parallel mode tests the hypothesis directly: if the slow runs vanish there, the parallel plan is the cause. Record per poller at the end of the run: `pg_prepared_statements` generic/custom counts for the `pages` statement, and the plan that actually ran (`auto_explain` with `log_min_duration` ~5 ms for the session, or an equivalent). Report: per pass `pages` p50/p95 and throughput, plan mode, plan. Also the same on `S8-S2` in the isolated bench with `--pages-client-sort` for `PF-NS`, `PF-ANY`.

## Ownership (user decision 2026-11-02)

Partial partition ownership is the default deployment. Primary measurements use `--owned mod4` (labels `S8-S2`, `S8-S4`, plus new `S8-S5`, `S8-S6`, `S8-S7`);
add a 1/16 ownership label on S2 and S4 (`S16-*`, explicit partition list) to see scaling with instance count; full ownership (`S2`, `S4`) is secondary.
Staged run: screen (P1 and P2 only, `--groups HOT,MON`) on S8-S2, S8-S4, S8-S7 + write cost on S2; confirm survivors on all labels with equivalence, S9, S10;
validate the top 2–3 at least twice, order swapped, container restarted, against v1b. Keep a variant at the screen if its plan changes on a P1/P2 query.

## Query set U (all variants unless noted)

v1 catalog, overridden by:
- **U1 `pages`** without `ORDER BY`: `SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL`.
  The repository contract keeps pages ordered by creation time and record id (`OutboxRecordRepository.findIncompleteRecordPages` KDoc), so the
  sort moves to the service: after `groupBy { recordKey }`, each page is sorted by `(createdAt, id)`.
- **U2 `oldest_due_strict` and `oldest_due_nonstrict`**: `+ AND o.completed_at IS NULL` on the outer query (neither has it in v1; without it they cannot use a partial index for `o`).
- `keys_*`, `oldest_created`, `count_statuses`, `count_partitions` unchanged. `count_partitions` is the `IN (:partitions)`
  form used by the observability collector (user confirmed 2026-11-02); `count_partition_single` is not a target.

Harness: option `--pages-client-sort` that performs group + sort of the fetched rows in the JVM inside the timed section, so
`pages` compares DB sort against DB fetch + memory sort at the same point. Also report DB-only time. Sort key: `created_at`, then `id` with
`String.compareTo`. Check: DB collation vs `compareTo` on the `id` tie-break, which must match the predecessor check `earlier.id < o.id`
(lower-case UUIDs agree; arbitrary ids may not, note it as a risk).

## Indexes

- **A**: `(status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key) WHERE completed_at IS NULL` (= E02b)
- **B1**: `(record_key, created_at, id) WHERE completed_at IS NULL` (= E04)
- **B2**: `(record_key, created_at, id) INCLUDE (status, next_retry_at) WHERE completed_at IS NULL`
- **C**: `(status, next_retry_at) INCLUDE (partition_no) WHERE completed_at IS NULL`
- **C2**: `(status, next_retry_at) INCLUDE (partition_no, record_key, created_at, id) WHERE completed_at IS NULL` (makes the outer side of `oldest_due_strict` index-only: the `NOT EXISTS` needs `record_key`, `created_at`, `id`)
- **D1**: `(status)`; **D2**: `(status, partition_no)` (both non-partial: `count_statuses` can include COMPLETED via `record-statuses`)

## Variants (PK always present; statistics object kept unless noted)

| Variant | Indexes | SQL | Question |
|---|---|---|---|
| `v1b`, `MIN` | from round 2 | round 2 | references, re-measured on these snapshots |
| `U0` | v1b DDL | U | U1 + U2 alone, no DDL change |
| `UAB1` | A, B1 | U | floor: how bad are the metrics without C/D? |
| `UAB2` | A, B2 | U | B2 INCLUDE: `keys_nonstrict` predecessor index-only? worth the size? |
| `UABC` | A, B1, C | U | C for `oldest_due_*` |
| `UAB2C` | A, B2, C | U | |
| `UABC2` | A, B1, C2 | U | C2 vs C: does index-only `oldest_due_strict` pay for the larger index and its write cost? `oldest_due_nonstrict` needs only C |
| `UABC2D2` | A, B1, C2, D2 | U | C2 in the full proposal, compared with `UABCD2` |
| `UABD1` / `UABD2` | A, B1, D1 / D2 | U | D without C |
| `UABCD1` / `UABCD2` | A, B1, C, D1 / D2 | U | full proposal |
| `UABC-L` | A, B1, C | U + literal `completed_at IS NULL` on `count_statuses` (NEW/FAILED form) and `count_partitions` | can A serve counts without D? `count_statuses` with COMPLETED keeps the original text |
| `UABCD2-NS` | as UABCD2, no statistics object | U | V1-3 under partial indexes (estimate on `completed_at IS NULL` comes from the partial index) |

### partition_no in the key (user idea 4; expected no gain or slower)

Each compared with its INCLUDE counterpart (`UABCD2`), same other indexes:
| Variant | Change | Expectation |
|---|---|---|
| `PA1` | A → `(status, partition_no, created_at, id) INCLUDE (next_retry_at, record_key)` partial | breaks global `ORDER BY created_at`: Sort over all due rows of the owned partitions; S4 much worse, but under partial ownership it skips foreign partitions: must be judged on S8/S16 |
| `PA2` | A → `(status, created_at, id, partition_no) INCLUDE (next_retry_at, record_key)` partial | same plan as A, filter on key column; no gain, size neutral |
| `PC1` | C → `(status, partition_no, next_retry_at)` partial | `oldest_due_*` per partition: MIN must scan or merge across partitions; S8 maybe better |
| `PC2` | C → `(status, next_retry_at, partition_no)` partial | same plan as C; no gain |

`E04` (round 2 winner: v1b DDL with B1 instead of the record-key index, round 2 SQL) is an additional reference. `MIN` is not re-measured (`UAB1` replaces it with the `oldest_due_*` literal).

## Stage 1: screen

- Variants: `v1b`, `E04`, `U0`, `UAB1`, `UAB2`, `UABC`, `UAB2C`, `UABC2`, `UABC2D2`, `UABD1`, `UABD2`, `UABCD1`, `UABCD2`, `UABC-L`, `UABCD2-NS`, `PA1`, `PA2`, `PC1`, `PC2`. The `PA*`, `PC*` variants otherwise have the `UABCD2` indexes.
- Labels: `S8-S2`, `S8-S4`, new `S8-S7` (S7 data, `--owned mod4`), `--groups HOT,MON`, warmup 5, reps 20, `--prepare`, rotated order (logged).
- `S16-S2`, `S16-S4` (1/16 ownership, explicit list of 16 partitions) for `v1b`, `UABCD2`, `PA1`, `PC1`.
- Write cost on S2 for all DDL variants (latency, WAL bytes/write, index count and sizes), twice for `v1b`, `UAB1`, `UABCD2`. WAL bytes is the write metric; bench write cells are not used to rank variants.
- Screen decision by the orchestrator: drop clearly worse variants, and variants with more write cost and no read gain. Keep a variant if its plan changes on a P1/P2 query.

## Harness changes before the screen (`R2-plan-modes.md` section 7)

1. Unique SQL comment `/* <query id> */` on every statement, so the `count_*` statements stop sharing a prepared statement and their plan mode.
2. `compare.py`: show plan mode from `plancache.csv` next to medians and flag cells whose modes differ.
3. `--pages-client-sort` (bench and S10 loop), `--pg-no-parallel`, S10 per-poller plan counts and the plan that ran.
4. Planning vs execution time reported separately where a plan is captured.

## Screen extension (user request 2026-11-02, after the confirm stage was stopped)

Label suffix `-R3X`, driver `bin/round3-screenx-pg.sh`. Same mechanics as stage 0 and stage 1. The confirm stage stays stopped.

**X0, `pages` forms in the S10 loop on the proposed indexes** (stage 0 ran only on v1b DDL, with the full record-key index). DDL sets: `UABCD2` (A, B1, C, D2) and `UABC` (A, B1, C). Forms: `PF-UN` (inner `ORDER BY r.created_at, r.id`), `PF-IN` (`ORDER BY record_key, created_at, id`), `PF-NS`, `PF-ANY`, `PF-UNNS0` (`unnest`, `OFFSET 0` fence, no inner `ORDER BY`, client sort). Pollers 4, rate 0, 60 s, 4 passes per combination, rotated order, default plan mode, plan capture.

**X1, screen variants** (`--groups HOT,MON`; labels `S8-S2`, `S8-S4`, `S8-S7`; write cost on S2). New index **C3**: `(status, next_retry_at) WHERE completed_at IS NULL`. References re-measured on these snapshots: `v1b`, `UABCD2`, `UABC-L`.

| Variant | Indexes | SQL | Question |
|---|---|---|---|
| `UABC3` | A, B1, C3 | U | C3 without D |
| `UABC3D2` | A, B1, C3, D2 | U | C3 in the full proposal, vs `UABCD2` |
| `UABC3-L` | A, B1, C3 | as `UABC-L` | C3 with literal counts (without `partition_no` in C, can `count_partitions` use A?) |
| `PL-A` | A → `(partition_no, status, created_at, id) INCLUDE (next_retry_at, record_key)` partial; B1, C, D2 | U | `partition_no` leading in the queue index |
| `PL-C` | A, B1, C → `(partition_no, status, next_retry_at)` partial, D2 | U | `partition_no` leading in the retry index |
| `PL-AC` | both of the above, B1, D2 | U | |
| `PAC` | A → `(status, partition_no, created_at, id) INCLUDE (next_retry_at, record_key)` partial, C → `(status, partition_no, next_retry_at)` partial, B1, D2 | U | `PA1` + `PC1` together |
| `PD` | A, B1, C, D2 → `(partition_no, status)` | U | `partition_no` leading in the count index |

New queue index variants (user request 2026-11-02): **A2**: `(status, created_at, id) INCLUDE (partition_no) WHERE completed_at IS NULL`; **A3**: `(status, created_at, id) WHERE completed_at IS NULL`. Without `next_retry_at` and `record_key` in the index, `keys_*` must read the heap for each candidate (A3: also for `partition_no`).

| Variant | Indexes | SQL | Question |
|---|---|---|---|
| `UA2BCD2` | A2, B1, C, D2 | U | vs `UABCD2` |
| `UA3BCD2` | A3, B1, C, D2 | U | vs `UABCD2` |
| `UA2BC-L` | A2, B1, C | as `UABC-L` | vs `UABC-L` |
| `UA3BC-L` | A3, B1, C | as `UABC-L` | vs `UABC-L` |

Report for A2, A3: index size, WAL per write, buffers and heap fetches of `keys_*`, not only time.

`S16-S2`, `S16-S4` for `v1b`, `UABCD2`, `PL-A`, `PL-C`, `PL-AC`.

## Stage 2: confirm (defined after the screen, see `R3-screen.md`, `R3-stage0-pages-loop.md`)

`pages` in every variant: `unnest` + `LATERAL`. With the partial record-key index the inner sort drops the constant column:
`... CROSS JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ORDER BY r.created_at, r.id) o`.
`oldest_due_*` with the outer literal (U2) in every variant except the reference.

| Variant | Indexes | Other SQL | Question |
|---|---|---|---|
| `v1c` | v1b DDL | v1 catalog with `unnest` `pages` (v1 text) | reference |
| `UABCD2` | A, B1, C, D2 | U2 | user's full proposal |
| `UABC-L` | A, B1, C | U2 + literal on `count_statuses` (NEW/FAILED), `count_partitions` | no D |
| `UABC-L-NS` | as UABC-L, no statistics object | same | is V1-3 still needed? |
| `UAB2C-L` | A, B2, C | same | B2 on S5/S6 |
| `UABC2-L` | A, B1, C2 | same | C2 on S5/S6 |
| `UABC-L-UNNS0` | A, B1, C | same + `pages` without the inner `ORDER BY`, `OFFSET 0`, client sort (`--pages-client-sort`) | client sort form with `OFFSET 0` |
| `UA3BC-L` | A3 `(status, created_at, id) WHERE completed_at IS NULL`, B1, C | same as UABC-L | A3 instead of A |
| `UA3BC3D2` | A3, B1, C3 `(status, next_retry_at) WHERE completed_at IS NULL`, D2 | as UABCD2 | smallest index set that keeps D2 |

Out of confirm (reasons in `R3X-screen-extension.md`): `UABC-L-UNNS` (superseded by the `OFFSET 0` form, no difference above S10 noise), `PL-*` and `PAC` (`partition_no` leading: `keys_strict` and `oldest_due_strict` 200-700 ms on S8-S4), `PD` (no `count_partitions` gain, `count_statuses` 107 vs 28 ms), `A2` (nothing over A3), `IN`/`= ANY` `pages` forms (slow plans in S10), C3 without D2 (`count_partitions` 119 vs 48 ms).

Labels: `S8-S2`, `S8-S4`, `S8-S5`, `S8-S6`, `S8-S7` (`--groups HOT,WRITE,MON`), then `S2`, `S4`, `S5`, `S6`, `S7` (all groups, so P3 regressions are reported). Warmup 5, reps 20, rotated order.
Equivalence vs v1 on S2, S5, S7 (`pages` per-key order; check strictly that the status mix and rows match; `S5` has many rows per key).
S9 churn for all variants. S10 (`keys_strict` loop, disjoint partitions): pollers 4, rate 0, **4 passes** per variant, pollers 1, 2 passes, order rotated, capture plans: check that `pages` never uses either queue index (`idx_outbox_record_status_created_id_incomplete`, `idx_outbox_record_status_retry_incomplete`) with a `record_key` filter, in the per-poller final captures and the auto_explain plans, also with B1 instead of the full record-key index.
Pinned plan modes on `S8-S2` and `S8-S4` for all variants (9): `force_custom_plan`, `force_generic_plan`, all HOT and MON queries.
Write cost on S4 (label `S4-wc`: index sizes and WAL on the larger queue) for `v1c`, `UABCD2`, `UABC-L`, `UA3BC-L`, `UA3BC3D2`. Write cost on S2 was measured in the screen; add `UABC-L-NS` (equal DDL except the statistics object), and repeat `UABC-L` once.
`count_statuses_all` (COMPLETED included via `record-statuses`) is reported per variant.

## Stage 3: validate (defined after confirm, see `R3C-confirm.md`)

Survivors from `R3C-confirm.md`: `v1c` (reference), `UABCD2`, `UABC-L`, `UABC-L-NS`. Override inputs are the existing `experiments/R3C-<variant>/`; no new variants. Equivalence is done in stage 2 and not repeated.
Driver `harness/bin/round3-validate-pg.sh`, suffix `-R3V`, labels `<label>-v<pass>-R3V`, logs `results/logs/round3-validate-pg.{log,out}`, failures `results/logs/failures-round3v.txt`.

Two passes of the whole matrix. Pass 2 runs the variants in reversed order of pass 1, and postgres is restarted (`bin/db.sh stop postgres`, then start) between the passes, so that order and restart effects show. Warmup 5, reps 20, `--prepare`. Per pass:
- **S2:** `S8-S2` (`--owned mod4`, `--groups HOT,WRITE,MON`), `S2` (all groups), write cost (`S2-wc`), S10 on S2, S9 churn.
- **S4:** `S8-S4`, `S4`, write cost with index sizes (`S4-wc`), S10 on S4 (the large queue; not run in stage 2).
- **S5:** `S8-S5`, `S5`. **S6:** `S8-S6`. **S7:** `S8-S7`.
- **S10:** `keys_strict` loop, pollers 4, rate 0, 60 s, `--capture-plans`, 2 runs per variant per pass with a fresh prepare before each, order rotated by 2 between the runs. Plan check as in stage 2: `pages` plans must use neither queue index.

Questions to settle: whether the `upd_retry` / `delete_by_id` gap on S4 (v1c faster) and the 47-49 % `upd_complete` gain on S4 repeat; D2 against no D2 (`count_partitions`, P1 `pages` on S8-S5); whether the statistics object is needed; S10 throughput on a large queue. Disk: the driver aborts below 12 GB free.

## Reports

`experiments/U-index-set.md` (per index: which P1/P2 query uses it, read gain, write cost, keep/drop), `experiments/U-pages-client-sort.md`,
`experiments/PA-partition-key.md`. Final recommended P1/P2 index set with evidence.

## Round 4 candidates

E40 UUIDv7, E07 autovacuum/fillfactor, E08 per-partition `LATERAL`, statement-cache effect of `IN` lists.
