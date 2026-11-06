# `keys_strict` / `keys_nonstrict`: scalar head instead of `NOT EXISTS` (PostgreSQL)

**Prepared 2026-11-06, not run.** Start with `harness/bin/khead-pg.sh` (it refuses to start while another `outbox-bench-*` container runs).

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

Not run yet.
