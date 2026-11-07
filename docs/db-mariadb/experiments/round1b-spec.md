# Round 1b spec — screen on the round-1a winner (2026-11-06)

Orchestrator spec. Stage **screen** (one pass). Brief `briefs/06-experiment.md`. Inputs: `03-main-vs-branch.md`, `experiments/r1a-index-sets.md`.
Noise thresholds and host-load rule: `STATE.md`.

## Base

**`b-base` = `r1-m1d` DDL (i1, i2, i3; `experiments/r1/r1-m1d/ddl.sql`) with the branch SQL.** Round 1a: it removes the full-ownership
collapse and the S4 write-cost increase. Its remaining weak cells (round 1a section 1):
- P2 `oldest_created` / `oldest_due_nonstrict` scan the NEW range: S4 202 / 205 ms, S16-S4 146 / 121 ms (main 11.6 ms for nonstrict).
- `count_partitions`: S16-S4 106 ms (main 7.5), S8-S5 25 (main 8.7).
- `count_statuses`: +25-42 % against main's two single counts (phase 3).
- `keys_strict` S8-S5 107 ms and `pages` S8-S5 / S5 1.3-1.6 s (hot keys; main 15 s / 5 s, so already a win, but the largest P1 values left).

`(status, partition_no)` is **not** carried into 1b: it doubles S4 insert redo and S2 completion/delete redo (round 1a section 3). It
only serves `count_partitions` (P2), which does not outweigh a P1 write cost under ADR-0005.

## Labels and limits

As round 1a: `S8-S4`, `S8-S5`, `S8-S7`, `S16-S4`, `S4`, `S5`, `S6`, `S7`. 10 s per-query limit on S4, S5, S6, S7, S8-S4, S16-S4,
elsewhere max(2 x base median, 60 s). `pages` on S4 with `--pages-keys-file experiments/r1/s4-pages-keys.txt`. DDL runs also get
`S2-wc`, `S4-wc` and `S10-S2-p1-unthr`. `b-base` first and again last in every scenario block (drift check).

## Part 1 — DDL runs

| Run | DDL | Queries | Question |
|---|---|---|---|
| `b-base` | i1, i2, i3 | branch | base (ref) |
| `b-e5l` | `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2, `(status, next_retry_at, partition_no)` (E5 without its 4th index) | pack `r1-qf14` (`experiments/r1/r1-e5q/queries/`) | E5 lean: do the E5 forms keep 0.2-0.6 ms without `(status, partition_no)`, and is the write cost at base level? (user idea) |
| `b-m4` | i1, `(record_key, completed_at, created_at)` (implicit PK suffix), i3 | branch | M4: same size and plans as i2 with explicit `id`? |

## Part 2 — query packs on the `b-base` schema (no re-prepare, `--only` the overlaid ids)

Overlays are copies of `Q-merged/<query>/<form>.sql`, saved as `experiments/r1b/<pack>/queries/<query id>.sql`. Index hints are left out
(they need a user decision and the round-1a winner has no problem a hint must fix). Forms that name B-1d (`cs-01`, `cp-01`, `cp-03`, `cs-07`,
`cp-05`) do not apply to this DDL.

| Pack | keys_strict | keys_nonstrict | pages | count_statuses | count_partitions | oldest_created | oldest_due_strict | oldest_due_nonstrict |
|---|---|---|---|---|---|---|---|---|
| `b-qf1` | ks-01-split | kn-01-split | pa-01-latejoin | cs-02-split-null-sum | cp-02-split-null | **oc-03-order-id** | ods-01-min | **odn-01-order** |
| `b-qf2` | ks-02-tuple | kn-02-tuple | pa-02-semi-in | cs-03-split-null-count | cp-04-incomplete (s-c) | oc-06-order-limit | ods-02-split | odn-02-min-of-limit |
| `b-qf3` | ks-03-leftjoin | kn-03-leftjoin | pa-05-setstmt-nomerge | cs-06-incomplete (s-c) | cp-06-group-sum (s-c) | oc-02-min-of-limit | ods-03-split-min | — |
| `b-qf4` | ks-04-leftjoin-straight | kn-04-leftjoin-straight | — | — | — | — | ods-04-leftjoin | — |
| `b-qf5` | ks-05-head | kn-05-head | — | — | — | — | ods-05-leftjoin-straight | — |
| `b-qf6` | ks-06-window | kn-06-window | — | — | — | — | ods-06-head | — |
| `b-qf7` | ks-07-window-part | kn-07-window-part | — | — | — | — | ods-07-window | — |
| `b-qf8` | — | kn-08-groupby | — | — | — | — | ods-08-min-of-limit | — |
| `b-qf9` | — | kn-09-groupby-minid | — | — | — | — | — | — |
| `b-m1q` | ks-00-main | kn-00-main | pa-00-main | — | — | oc-00-main | ods-00-main | odn-00-main |

(s-c) = status-conditional (valid only when the code selects it for NEW/FAILED).
Mandatory experiments covered:
- **M2:** `oc-00-base` (MIN, base) vs `oc-06-order-limit` / `oc-03-order-id`.
- **M3:** `ods-00-base` vs `ods-01-min`; `odn-00-base` vs `odn-01-order`.
- **M6:** all `ks-*` / `kn-*`; report S8-S5 and S8-S7 first.
- **M1 query reverts:** pack `b-m1q`.
- **M7:** round 1a `r1-a1` was a loss, so i1, i2, i3 is the minimal set unless a form of `oldest_due_strict` works without i3. Check `ods-*` on `r1-a1`'s DDL only if a form reaches < 1 ms on `b-base` without using i3.

The candidate this round is expected to produce is **`b-base` + `oc-03-order-id` + `odn-01-order`**: ordered scans on i1 and i3 that
stop at the first owned row. Report it explicitly, with its plans, at every label.

## Part 3 — M5 `pages` (after parts 1 and 2)

Forms `pa-00-base`, `pa-01-latejoin`, `pa-02-semi-in` and the code-class derived key tables `pa-06-json-table`, `pa-08-values-cte`,
`pa-09-union-per-key` on `b-base`, labels `S8-S5`, `S5`, `S4-b100` (keys file for S4-b100: the first 100 keys of the same fallback
order). The harness may be extended minimally to bind them: `:recordKeysJson` (a JSON array string of the keys), a `(?),(?)` row
expansion, and a per-key UNION ALL expansion. Document the extension in the harness README.

## Equivalence

Every pack and `b-e5l`: `equiv` against `mdb-opt` on S1 and S5. Survivors of this screen get S7 too before confirm. Report any FAIL or
WARN prominently.

## Report

`experiments/r1b-forms.md` in brief 06 format:
- Per part, partial ownership first.
- Per query: the best form against `b-base` and against `mdb-main` (phase 3).
- Plans of the winners.
- A ranking.
- Deferred-test candidates.
- A proposed confirm list: the best form per query on the best DDL.
