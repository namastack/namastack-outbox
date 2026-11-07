# Confirm stage spec (2026-11-06)

Orchestrator spec. Stage **confirm + validate** in one run: every label, two passes in reversed order with a container restart in
between, solo cells as usual. Brief `briefs/06-experiment.md`. Inputs:
- `03-main-vs-branch.md`;
- `experiments/r1a-index-sets.md`, `r1b-forms.md`, `r2-fourth-index.md`;
- noise thresholds and the host-load rule in `STATE.md`.

**Ids: UUIDv4 only (user decision 2026-11-06).** Never set `HARNESS_ID_VERSION`. Check that every snapshot marker has no `ids=uuidv7`
line. The round-2 v7 snapshots must be regenerated.

## Runs

| Run | DDL | Queries |
|---|---|---|
| `c-ref` | branch (`mdb-opt` worktree DDL) | branch SQL |
| `c-1` | `experiments/r2/spc/ddl.sql` | branch SQL + `experiments/r2/forms-a/queries/` |
| `c-2` | `experiments/c/c-2/ddl.sql` | branch SQL + `experiments/r2/forms-e5h/queries/` |

- **`c-1` indexes:**
  - i1 `(status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`;
  - i2 `(record_key, completed_at, created_at, id)`;
  - i3 `(status, completed_at, next_retry_at, partition_no)`;
  - `idx_outbox_record_status_partition_created (status, partition_no, created_at)`.
- **`c-1` forms:** `ks-05-head`, `kn-05-head`, `oc-03-order-id`, `odn-01-order`.
- **`c-2` DDL:** write it as `experiments/r1b/b-e5l/ddl.sql` plus `idx_outbox_record_status_partition_created (status, partition_no, created_at)`.
  The E5 indexes are `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2 and `(status, next_retry_at, partition_no)`.
- **`c-2` forms:** `ks-11`, `kn-14`, `oc-08`, `odn-06`, `ods-09`. They rely on the NEW <=> `completed_at IS NULL` invariant, which the
  user accepted as a known rule.
- **`mdb-main` is not re-run.** Its values come from `results/cmp/SUMMARY.md` (same harness, v4), and phase 6 measures main, branch and
  final side by side.

## Labels

Every label measured in phase 3, except S2L:
- **Bench:** `S1`, `S2`, `S4`, `S4-b100`, `S5`, `S6`, `S7`, `S8-S2`, `S8-S4`, `S8-S5`, `S8-S6`, `S8-S7`, `S16-S2`, `S16-S4`.
- **Write cost:** `S2-wc`, `S4-wc`.
- **Churn:** `S9`, pass 1 only, `--retention-max-s 2700` (as in phase 3).
- **Concurrency:** `S10-S2-p1`, `S10-S2-p1-unthr`, `S10-S2-p4`, `S10-S2-p4-unthr`, `S10-S4-p4`, `S10-S4-p4-unthr`.

Groups `HOT,WRITE,MON`.

**`pages` keys:**
- On S4 and S4-b100, use `--pages-keys-file` (`experiments/r1/s4-pages-keys.txt`, `s4-pages-keys-b100.txt`) for all three runs.
- Elsewhere, `pages` uses each run's own `keys_strict` keys.

**Per-query limit:**
- `c-1` and `c-2`: max(2 x `c-ref` median, 60 s).
- `c-ref`: 10 s on S4, S4-b100, S5, S6 and S7. Its collapse there is known from phase 3, so don't spend minutes on it. Elsewhere 60 s.

**Order:**
- Pass 1: `c-ref`, `c-1`, `c-2`, scenarios in the phase-3 order.
- Container stop/start.
- Pass 2: `c-2`, `c-1`, `c-ref`, scenarios reversed.
- Within a cell, the three runs go back to back on the same snapshot (gen once per scenario and pass).
- Timings serial in lane 1, lane 2 idle during every timed step. Writecost, S9 and S10 are solo cells.
- Equivalence may run in lane 2 only while lane 1 is not timing.

## Equivalence

`c-1` and `c-2` against `mdb-opt` on S1, S2, S5 and S7 (`HARNESS_SAMPLE_ORDERED=1`). A FAIL ends the candidate as `invalid`. Report it
at once in `results/c/failures.txt`.

## Isolation test (read penalty of the 4th index, round 2 deferred item)

Round 2 found that adding the 4th index slows `keys_*` and `oldest_*` by 0.05-0.25 ms, with unchanged plans. Determine whether this is
planning or execution:
- **Data:** labels `S8-S4`, `S16-S4`, `S8-S5`, `S4`.
- **Runs:**
  - `c-1`;
  - `c-1-no4` = `experiments/r2/base/ddl.sql` with the same forms.
- **Timings:** bench medians, one pass, back to back on the same snapshot.
- **Planning time:** for the queries `keys_strict`, `keys_nonstrict`, `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`:
  - time `EXPLAIN FORMAT=JSON` of each query, executed through the same JDBC path (20 reps, median), in both schemas;
  - report the `optimizer_trace` range-analysis summary in both schemas (number of ranges or index dives considered per index).
- **Verdict:** is the difference planning, execution, or noise?

## Report

`experiments/confirm.md`, in brief 06 format:
- Per label, partial ownership first: `c-ref`, `c-1` and `c-2`, pass 1 / pass 2, with the change against `c-ref` and against `mdb-main`
  (phase 3).
- `SPREAD>2x` flags.
- Plans for every query > 5 ms and for every regression.
- Write cost and index sizes.
- S9 and S10.
- Equivalence.
- The isolation test.
- Verdict per candidate: `win` / `no difference` / `loss` / `mixed` / `invalid` against `c-ref` and against `mdb-main`, by the ADR-0005
  rule (P1/P2; P3 not judged).
- A recommendation `c-1` vs `c-2`. Note: `c-2` gets no penalty for the invariant. If equal within noise, report "no difference" and list
  the measurable differences (size, write cost).
- Deferred-test candidates.
