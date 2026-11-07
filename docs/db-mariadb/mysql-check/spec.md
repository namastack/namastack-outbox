# MySQL check spec — do the MariaDB form results carry over to MySQL? (2026-11-07)

Orchestrator spec, user request 2026-11-07. Short experiment, stage `screen`. Brief `briefs/06-experiment.md` (adapted: dialect `mysql`).
Everything of this experiment lives in `docs/db-mariadb/mysql-check/` (results in `mysql-check/results/`). It is independent of any
other MySQL work in the repository.

## Setup

- MySQL `mysql:9.2` (the integration-test image; harness port 13306), harness dialect `mysql`. The harness was validated on MariaDB;
  first smoke-test it on MySQL (gen + prepare + bench S1, equivalence S1 of the base against itself) and fix minimal issues (document in
  the harness README). Record version, buffer pool size and `eq_range_index_dive_limit` in `env.txt`. Use the default MySQL server
  settings of the harness (no tuning).
- Code: current `origin/feature/db-jdbc-optimization` = `4037faa5` (worktree `/home/azamojski/outbox-wt-mdb-opt2`, catalog
  `harness/catalog/mdb-opt2/mysql/`, already extracted). DDL: that branch's `schema/mysql/outbox-tables.sql` (status indexes without
  `completed_at`, 4th index `(status, partition_no)`).
- **Ids UUIDv7:** `HARNESS_ID_VERSION=7` for `gen` and for every `prepare`, `bench`, `equiv` step; check the snapshot marker
  (`ids=uuidv7`) before each timed step; record the id version in every result tree.

## Forms (overlays on the `mdb-opt2` MySQL catalog, `--queries DIR`)

The base forms are the branch's MySQL statements. Write each alternative as one overlay file `<query id>.sql` (header copied from the
catalog file, only the SQL changed), all predicates otherwise identical to the branch statement (no `completed_at` predicate added):

| Query | A (branch, base) | B |
|---|---|---|
| `oldest_created` | `SELECT created_at ... ORDER BY created_at LIMIT :limit` (limit 1) | `SELECT MIN(created_at) ...` (no ORDER BY / LIMIT) |
| `oldest_due_nonstrict` | `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT :limit` | `SELECT MIN(next_retry_at) ...` |
| `oldest_due_strict` | `SELECT o.next_retry_at ... AND NOT EXISTS (...) ORDER BY o.next_retry_at LIMIT :limit` | `SELECT MIN(o.next_retry_at) ... AND NOT EXISTS (...)` |
| `keys_strict` | head form `o.id = (SELECT e.id ... ORDER BY e.created_at, e.id LIMIT 1)` | `NOT EXISTS` predecessor check = `DefaultJdbcOutboxRecordQueries` at `4037faa5` (`$noEarlierIncompleteRecord`) |
| `keys_nonstrict` | head form | `NOT EXISTS` (`$noEarlierDueRecord`) |

Take the B texts from `DefaultJdbcOutboxRecordQueries.kt` in the `4037faa5` worktree (render `$tableName` = `outbox_record`,
`$rowLimitClause` = `LIMIT :limit`); for the MIN forms the `maxrows` header must be `none` and `:limit` must not appear.
Overlay sets: `mysql-check/forms-b/` (all five B forms). Run A = catalog alone, run B = catalog + `forms-b`.

## Equivalence

B against A on S1 and S5 (v7, `HARNESS_SAMPLE_ORDERED=1`). For MIN vs ORDER BY the single value must be equal. A FAIL is reported
and the form is `invalid`.

## Timings

Labels, partial ownership first: `S8-S2`, `S8-S4`, `S16-S4`, `S8-S5`, `S4`, `S5`, `S7`. Groups `HOT,MON` (the five queries are what
matters; the rest is context). Per label gen once (v7), then A, B, A (drift check) back to back on the same snapshot. Lane 1 only,
nothing else measuring. Per-query limit 60 s; record a stopped query as `> 60 s`. `pages` on S4 with
`--pages-keys-file experiments/r1/s4-pages-keys.txt` only if those keys exist in the v7 data, otherwise its own `keys_strict` keys.
Capture plans (`EXPLAIN ANALYZE`) for the five queries in both runs and `handlers.csv` (rows examined).

Host-load rule and noise thresholds from `STATE.md` (HOT 11 %, MON 10 % / 0.04 ms).

## Report

`mysql-check/REPORT.md`, short:
- setup (MySQL version, settings, ids v7, code sha), harness changes;
- per query and label: A vs B medians, ratio, verdict (thresholds), partial ownership first;
- plans: index chosen, rows vs actual rows, whether the ORDER BY form stops early and whether MIN does;
- equivalence;
- **per comparison: does the MariaDB result carry over?** The MariaDB results to compare against (from `04-exploration.md`,
  `experiments/r1b-forms.md`, `results/final/SUMMARY.md`): (1) `ORDER BY ... LIMIT 1` beats `MIN` for `oldest_created` and
  `oldest_due_nonstrict` because MIN cannot stop early when `partition_no` follows the MIN column in the index (MariaDB: S8-S4
  `oldest_created` MIN 161 ms vs ordered 0.26 ms; S16-S4 5 s vs 0.2 ms); (2) `oldest_due_strict`: the ordered `NOT EXISTS` form was kept
  (no better form found); (3) head form beats `NOT EXISTS` for `keys_*` at hot keys (S8-S5 `keys_strict` 107 -> 54 ms,
  `keys_nonstrict` 4.9 -> 0.6 ms; S5/S7 seconds -> sub-ms), equal elsewhere. Note the DDL differs from the MariaDB final design (no
  `completed_at` in the status indexes); say where that limits the comparison.
