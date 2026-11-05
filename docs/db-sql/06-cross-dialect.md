# Current `main` vs `feature/db-jdbc-optimization`, other databases

Driver `harness/bin/cmp-dialects.sh`. `cmp-main` = `f4446c8`, `cmp-jdbc-opt` = `01165d15`, each with its own shipped DDL and SQL. Timings for the HOT, WRITE and MON groups only, one run per cell, so differences near the thresholds are not conclusive. Results `results/<variant>/<dialect>/<label>/timings.csv`. Cells: median ms. Sections are added as each database finishes. Plan: `07-next-runs-plan.md`.

Per-query limit: `cmp-main` 900 s, branch max(2 × `cmp-main` median, 60 s). A stopped query has no timing.

## H2

Equivalence on S1 and S5: 28 and 26 PASS, no WARN, FAIL or ERROR. No failures.

**The branch makes two H2 queries far slower: `pages`, in the default mode, and `keys_nonstrict`, in the non-default mode.** The other HOT, WRITE and MON queries stay below about 1.4 ms on both sides, apart from `keys_strict` and `keys_nonstrict` in the table below and S5 `pages`, with no consistent difference.

| Cell | `main` | branch |
|---|---|---|
| S2 `pages` | 0.72 | **481** |
| S8-S2 `pages` | 0.47 | **476** |
| S4 `pages` | 0.45 | **1239** |
| S8-S4 `pages` | 0.45 | **1236** |
| S7 `pages` | 0.47 | **559** |
| S5 `pages` | 582 | 667 |
| S4 `keys_nonstrict` | 0.25 | **stopped at 60 s** |
| S8-S4 `keys_nonstrict` | 0.20 | **stopped at 60 s** |
| S7 `keys_nonstrict` | 0.27 | 0.45 |
| S4 `keys_strict` | 35 359 (1 execution) | 0.34 |
| S8-S4 `keys_strict` | 0.21 | 0.22 |

- **`pages` cause:** the branch adds `completed_at IS NULL`, and the plan uses `idx_outbox_record_key_completed_created_id` with `scanCount: 2000001`, a scan of the whole table's entries (`cmp-jdbc-opt/h2/S4/plans/pages.txt`). `main` uses `(record_key, created_at)` with `scanCount: 11` (`cmp-main/h2/S4/plans/pages.txt`). That H2 does not combine the `IN` list with the `IS NULL` condition on this index is inferred from the plan and not tested separately.
- **`keys_nonstrict` cause:** the branch's H2 form is a `NOT EXISTS` over `outbox_record`, where `main` uses `GROUP BY` with `MIN`. The time is spent in that subquery (a stack trace of the stopped run, and the non-executing plan `cmp-jdbc-opt/h2/S4/plans/keys_nonstrict.txt`). Its real duration is unknown: over 1 h 58 min in the earlier run.
- **`keys_strict` on S4:** `main`'s 35 s is one execution from the run stopped on 2026-11-04, before this file's limit and without the file restore. S8-S4 shows no difference, so this cell is not treated as a branch win.
- **The per-query limit** stopped only `keys_nonstrict` on S4 and S8-S4, so it stopped no healthy query.
- **Scale:** the cost follows table size, with about 2 million rows on S4. H2 is usually used with small tables, so the effect there may be small; this was not measured.
- **Not measured:** H2 under concurrency, and write cost beyond S2.
