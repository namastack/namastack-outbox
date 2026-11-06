# `keys_strict` / `keys_nonstrict`: scalar head instead of `NOT EXISTS` (PostgreSQL)

**Run 2026-11-06, PostgreSQL only: 88 min (18:18:46 to 19:46:44), 0 failures** (`results/logs/khead-pg.log`, no `fail` or `error` line, ends with `DONE khead`). Driver `harness/bin/khead-pg.sh` (it refuses to start while another `outbox-bench-*` container runs).

## Hypothesis (user idea)

Select a row only if it is the head of its key, found by a scalar subquery, instead of checking that no earlier record exists:

```sql
-- strict
... AND o.id = (SELECT e.id FROM outbox_record e
                WHERE e.record_key = o.record_key AND e.completed_at IS NULL
                ORDER BY e.created_at, e.id LIMIT 1)
-- non-strict: the subquery also has e.status = :status AND e.next_retry_at <= :now
```

- **Same result by reasoning:** `(created_at, id)` is a total order and `o` itself satisfies the subquery, so it never returns `NULL`. Equivalence is still checked below.
- **Expected on PostgreSQL:** no difference by the threshold. Both forms do one probe of `idx_outbox_record_key_created_id_incomplete` per candidate. The scalar form always runs as a per-row SubPlan and can never become a hash anti-join, so it removes a plan risk rather than adding speed.
- **Risk:** the planner cannot estimate `o.id = (SubPlan)` and uses a default selectivity. It may then leave the ordered queue-index scan with early stop for a bitmap scan plus sort. Watch S4 (every row qualifies) and generic plans.
- **No expected change** on hot keys (S5, S8-S5): the outer scan still walks every blocked due row of a hot key.

## Variants

| Variant | `keys_strict`, `keys_nonstrict` | `pages` | DDL |
|---|---|---|---|
| `K-REF` | branch `NOT EXISTS` (catalog `cmp-jdbc-opt`) | `P-KK2` | `feature/db-jdbc-optimization` at `270c42cf` (`../outbox-wt-khead`) |
| `K-HEAD` | `experiments/K-HEAD/queries/` | `P-KK2` | same |

Both get `P-KK2` `pages`, which is the branch's `pages` at `270c42cf`. The base catalog `cmp-jdbc-opt` was extracted at `01165d15`; its PostgreSQL `keys_strict`, `keys_nonstrict`, `insert` and `upd_complete` SQL and the PostgreSQL DDL are the same at `270c42cf`, so no new catalog extraction is needed.

## Matrix

- Two passes, order reversed, container restart in between; fresh `gen` per scenario and pass.
- Per scenario: `S8-<S>` (partial ownership, ranked first) and `<S>` (full), `--only keys_strict,keys_nonstrict`, 5 warmup + 20 reps. Scenarios S2, S4, S5, S6, S7.
- Equivalence `K-HEAD` vs `K-REF` on S2, S5, S6, S7 (pass 1).
- S10 (4 pollers, 60 s, 2 runs, order rotated) on S2 and S4 (pass 1): throughput, `keys_query_ms`, auto_explain of statements ≥ 5 ms.
- HOT threshold: 30 % and over 0.15 ms (`STATE.md`).

## Read after the run

- `plans/keys_strict.txt`, `plans/keys_nonstrict.txt` per cell: index used, Sort or Bitmap nodes, SubPlan loops, buffers.
- `plancache.csv`: custom vs generic, and whether timings and the captured plan disagree.
- S10: `concurrency-p4-r0.txt` (`processed_per_s`, `keys_query_ms`) and any slow `keys_strict` in the auto_explain log.

## Results

Results under `results/{K-REF,K-HEAD}/postgres/<label>-v<1|2>-KHEAD/`. Cells: median ms, pass 1 / pass 2 (`timings.csv`). Threshold for HOT from `STATE.md`: 30 % and over 0.15 ms. Pass 1 `S8-S2`, `S2` and `S10-S2` ran while an idle MySQL container (about 0.7 % CPU) was still up; from `S4` on the machine was quiet. Both variants of a label ran back to back under the same conditions, and `S8-S2` shows the same result in pass 2.

Verdict: **the scalar head is not adoptable.** It is a loss on both queries on `S8-S2` and `S8-S7` (partial ownership), where the outer scan leaves the ordered queue index for a partition index plus Sort. In the other 16 cells per query it is no difference or passes disagree, never a win. The S10 loop is slower on S2 (about -20 % throughput). The expectation "no difference" held only for full ownership and for hot keys.

### `keys_strict`

| Cell | `NOT EXISTS` (K-REF) | Scalar head (K-HEAD) | Change | Result |
|---|---|---|---|---|
| S8-S2 | 0.81 / 0.92 | 1.34 / 1.42 | +65 %, +54 % | loss |
| S8-S7 | 0.89 / 0.95 | 8.33 / 8.97 | +836 %, +842 % | loss |
| S2 | 1.10 / 1.47 | 1.09 / 1.05 | -1 %, -28 % | no difference |
| S8-S4 | 0.85 / 0.81 | 0.78 / 0.81 | -9 %, 0 % | no difference |
| S4 | 1.21 / 1.19 | 1.02 / 1.01 | -16 %, -15 % | no difference |
| S8-S5 | 166.0 / 164.3 | 150.7 / 152.8 | -9 %, -7 % | no difference |
| S5 | 1.39 / 1.26 | 1.19 / 1.13 | -14 %, -10 % | no difference |
| S8-S6 | 0.95 / 0.90 | 0.81 / 0.89 | -15 %, -2 % | no difference |
| S6 | 1.22 / 1.40 | 1.07 / 1.05 | -12 %, -25 % | no difference |
| S7 | 1.14 / 1.00 | 0.98 / 1.13 | -14 %, +13 % | no difference |

### `keys_nonstrict`

| Cell | `NOT EXISTS` (K-REF) | Scalar head (K-HEAD) | Change | Result |
|---|---|---|---|---|
| S8-S2 | 1.21 / 0.98 | 15.03 / 15.22 | +1139 %, +1460 % | loss |
| S8-S7 | 0.88 / 0.93 | 8.75 / 8.84 | +900 %, +849 % | loss |
| S8-S5 | 0.99 / 1.23 | 0.96 / 0.82 | -3 %, -33 % | passes disagree |
| S2 | 1.10 / 1.41 | 0.99 / 1.05 | -10 %, -25 % | no difference |
| S8-S4 | 0.93 / 0.84 | 0.72 / 0.79 | -22 %, -6 % | no difference |
| S4 | 1.16 / 1.18 | 0.98 / 0.97 | -15 %, -18 % | no difference |
| S5 | 1.29 / 1.21 | 1.08 / 1.06 | -16 %, -12 % | no difference |
| S8-S6 | 0.94 / 0.93 | 0.83 / 1.03 | -12 %, +11 % | no difference |
| S6 | 1.11 / 1.10 | 0.98 / 1.10 | -12 %, 0 % | no difference |
| S7 | 1.08 / 0.99 | 0.97 / 1.02 | -10 %, +3 % | no difference |

K-HEAD is nominally faster in most of the small cells (10 to 25 %), but none clears the threshold in both passes. `S8-S5` `keys_strict` is about 150 to 166 ms in both variants: a hot key makes the outer scan walk every blocked row (33 992 SubPlan loops, `K-HEAD/postgres/S8-S5-v1-KHEAD/plans/keys_strict.txt`; 33 992 probes, `K-REF/postgres/S8-S5-v1-KHEAD/plans/keys_strict.txt`). The scalar form does not change that.

### S10 (4 pollers, 60 s, pass 1, `concurrency-p4-r0.txt`)

| Scenario | Run | K-REF rec/s | K-HEAD rec/s | Change | K-REF keys p50 / p95 ms | K-HEAD keys p50 / p95 ms |
|---|---|---|---|---|---|---|
| S2 | r1 | 1270.2 | 1013.4 | -20 % | 4.39 / 6.21 | 8.69 / 47.38 |
| S2 | r2 | 1274.3 | 1055.9 | -17 % | 4.69 / 7.61 | 6.09 / 35.92 |
| S4 | r1 | 1128.2 | 1067.7 | -5 % | 5.15 / 7.22 | 4.62 / 6.33 |
| S4 | r2 | 1183.1 | 1101.6 | -7 % | 2.97 / 6.40 | 3.00 / 6.14 |

- **S2:** K-HEAD is slower in both runs. The key query p95 is 6 to 8 times higher. Loss. (Pass 1 S2 ran next to the idle MySQL container; both variants shared that condition and both runs agree.)
- **S4:** K-HEAD throughput is 5 to 7 % lower with equal key query p50 and p95. Small, in the same direction in both runs, no key query cause visible. No difference by key query time; the throughput gap is not explained.
- All runs: `errors: 0`. The S10 `plans-*` captures hold only `pages` plans, so the `keys_strict` plan in the loop is not saved (`concurrency-p4-r0.plans.txt`: "pages statements >= 5 ms logged"). The auto_explain lines are not in the results.

## Plans (per cell, custom plans)

All cells ran custom plans for the timed reps in the cells read (`plancache.csv` of `S8-S2`, `S2`, `S4`, `S5`, `S8-S7`, both variants: `custom_timed=20`, `generic_timed=0`). So the captured plan is the plan that was timed.

- **Outer scan, full ownership and `S8-S4`, `S8-S5`, `S8-S6`:** K-HEAD keeps `idx_outbox_record_status_created_id_incomplete` as an Index Only Scan with early stop, no Sort, SubPlan loops = 10 (e.g. `K-HEAD/postgres/S4-v1-KHEAD/plans/keys_strict.txt`, `S2-v1-KHEAD`, `S6-v1-KHEAD`). Buffers 37 to 52. Timings match K-REF.
- **Outer scan leaves the queue index on `S8-S2` and `S8-S7`, in both passes, both queries:** `Index Scan using idx_outbox_record_status_partition_incomplete` + `Sort` (top-N heapsort), no early stop. The outer reads all candidates and runs the SubPlan for each.
  - `S8-S2` `keys_strict`: 229 SubPlan loops, 974 buffers, 0.82 ms (`K-HEAD/.../S8-S2-v1-KHEAD/plans/keys_strict.txt`). K-REF: Nested Loop Anti Join over the queue index, 40 buffers, 0.135 ms (`K-REF/.../plans/keys_strict.txt`).
  - `S8-S7` `keys_strict` and `keys_nonstrict`: 2510 SubPlan loops, 10 258 and 12 559 buffers, 8.7 and 9.1 ms (`K-HEAD/.../S8-S7-v1-KHEAD/plans/`). K-REF: 10 rows read, 46 buffers, 0.106 ms (`K-REF/.../S8-S7-v1-KHEAD/plans/keys_nonstrict.txt`).
  - The outer row estimate is low in K-HEAD (rows=2 on `S8-S2`, rows=14 on `S8-S7`) against rows=2467 for K-REF on `S8-S7`. This points to the default selectivity of `o.id = (SubPlan)`, but the cause is inferred from the estimates; no statistics were inspected.
- **Hot key (`S8-S5` `keys_strict`):** same shape as K-REF (outer queue index, 33 992 probes, 115 084 buffers in both), so no change (`plans/keys_strict.txt` of both).
- **SubPlan index:**
  - `keys_strict`: always `idx_outbox_record_key_created_id_incomplete`, Index Only Scan, `Index Searches` = loops.
  - `keys_nonstrict`: on `S2`, `S5`, `S8-S5` and `S8-S2` the SubPlan uses `idx_outbox_record_status_created_id_incomplete` (filter on `record_key`, `next_retry_at`). On `S4`, `S8-S4`, `S6`, `S8-S6`, `S7`, `S8-S7` it uses the key index.

## Hypotheses checked

1. **Non-strict SubPlan on the queue index: confirmed for `S8-S2`, but it is not the main cause and it does not scale with `S4`.**
   - `S8-S2`: the SubPlan reads the queue index and removes 600 entries per probe, 229 loops, 4035 buffers (`K-HEAD/.../S8-S2-v1-KHEAD/plans/keys_nonstrict.txt`). That explains 15 ms against 8.7 ms for `S8-S7` `keys_nonstrict`.
   - The same SubPlan index appears on `S2`, `S5`, `S8-S5`, but only 10 to 19 loops with 2 to 111 rows removed, so the cost is small (0.12 to 0.25 ms).
   - On `S4` (1M NEW) and `S8-S4` the planner uses the key index with 10 loops, 54 buffers (`S4-v1-KHEAD/plans/keys_nonstrict.txt`). The hypothesis that it gets worse with 1M NEW is refuted.
   - The slowdown needs two things together: outer scan without early stop (many loops) and a SubPlan that walks the queue index. `keys_strict` on `S8-S2` has the first without the second and is still a loss (0.8 to 1.4 ms, small in absolute terms).
2. **Outer scan leaves the ordered queue index for a partition index plus Sort: confirmed for the per-cell plans on `S8-S2` and `S8-S7` (see above), not on full ownership.** For the S10 loop it is not verified: the loop plans are not saved. The S2 loop result (p95 36 to 47 ms against 6 to 8 ms, `concurrency-p4-r0.txt`) is consistent with it, but a run showing the `keys_strict` plan in the loop is needed. S4 in the loop shows no key query loss.
3. **Subquery `ORDER BY e.record_key, e.created_at, e.id` (not measured, reasoning only).** It could only affect the SubPlan index choice in (1). It cannot fix the outer plan in (2), which is where most of the loss comes from. Whether it changes the SubPlan plan is open: `record_key` equals an outer parameter in the subquery, so the planner may drop it from the sort key as redundant, which would leave the plan unchanged. Treat as untested.

## Equivalence (pass 1)

`results/equiv/postgres/{S2,S5,S6,S7}/K-HEAD-vs-K-REF.md`: 26 PASS each, no WARN, FAIL or ERROR. `keys_strict` and `keys_nonstrict` return 10 keys in the same order in all four. No S4 equivalence was requested.

## Limits

- PostgreSQL only. Other dialects were not measured.
- Equivalence checks 10 keys per scenario; the equal result comes from the ordering argument above plus these checks.
- Plans in the S10 loop are not saved, so hypothesis 2 for the loop is open.
- S10 is pass 1 only, 2 runs per variant.
- `S8-S2` and `S8-S7` are the only cells with the plan change; a different partition mix could change which cells flip.
- The result for `S8-S2` pass 1 was taken with an idle MySQL container running; pass 2 on a quiet machine agrees.
