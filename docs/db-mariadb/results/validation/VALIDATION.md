# Harness validation (S1, MariaDB 12.3.3, throw-away results)

Date 2026-11-05. Lane 1 (`outbox-bench-mariadb-L1`, port 13407) and lane 2 (port 13507). Variants: `mdb-main` f4446c8c, `mdb-opt` **2a2663de2f2028790180399293981f7eca587ad1**
(worktree moved from a06a74c0 on the orchestrator's instruction; all mdb-opt evidence below was re-run on 2a2663de; `oldest_created` is now `SELECT MIN(created_at) ... AND completed_at IS NULL`,
`maxrows: none`; the extractor's `maxNew` for it was fixed from `one` to `none`). Worktrees clean (`git status`). `hashcheck`: 0 mismatches of 20004 for both (re-run for mdb-opt after the move).

## What was run

| Step | Result | Where |
|---|---|---|
| gen S1, bench `--prepare --groups HOT,WRITE,MON,RET` both variants | ok, 25/27 queries, 0 errors | `mdb-*/mariadb/S1/` |
| plans + `summary.txt`, `handlers.csv`, `env.txt` | present for every query | same |
| writecost `--n 120` (S1 has 150 due ids, update phases limited to 50) | all 6 phases, redo/pages/data deltas, per-index sizes | `mdb-*/mariadb/S1/writecost.csv` |
| concurrency 20 s, 4 pollers, rate 500; also 1 poller, rate 0 (10 s) | 0 errors, ~497 rec/s processed, lock waits 0, deadlocks 0 | `mdb-*/mariadb/S1/concurrency-*.txt` |
| churn 20 000 inserts (S9 path) | sizes + history list length + counters | `mdb-opt/mariadb/S9v/churn.txt` |
| equivalence main vs opt, S1 | all PASS (see below) | `equiv/mariadb/S1/mdb-main-vs-mdb-opt.md` |
| repo-level JDBC `--dialect mariadb`, both variants | 25/26 methods ok | `mdb-*/mariadb/S1/repo-jdbc/` |
| `limitcheck --dialect mariadb --query-limit-s 3` | `limitcheck mariadb: 3.2 s: stopped at limit 3 s` | |
| `--limit-from` smoke bench | ok | `mdb-opt/mariadb/S1-lim` |
| `bin/run-cells.sh` (bench, writecost, concurrency, churn, equiv; reverse order; second call skips) | ok | `cells/`, `cells/logs/vcells-L1.log` |
| `bin/noise.py` self-test | ok | `noise-selftest.md` |
| two lanes at once (gen, bench, writecost, equiv on S1 each) | see below | `twolane/` |

## setMaxRows / SQL_SELECT_LIMIT evidence

1. Driver: `jdbc:mariadb://...` (Connector/J 3.5.10) in `Dialect.connect`, `ps.maxRows = n` in `Bench.exec` for every catalog entry with `maxrows` != none.
2. `general_log` (TABLE) switched on briefly in the lane-1 container while `bench --only find_completed_before,ids_completed_before` ran with `mdb-main` (log switched off and truncated afterwards).
   The server received, verbatim:
   `SET STATEMENT SQL_SELECT_LIMIT=1000 FOR SELECT * FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < '2025-12-25 12:00:00' ORDER BY completed_at`
   (and the same for `SELECT id ...`); parameters are inlined by the client-side prepared statement.
3. The limit is optimizer-relevant (`sql_select_limit_handlers.txt`, session `Handler_read_*`): unlimited `SELECT id ... ORDER BY completed_at` = full scan (`rnd_next` 10001, `rnd` 7532);
   `SET STATEMENT SQL_SELECT_LIMIT=1000 FOR ...` and `... LIMIT 1000` give identical counters (`key` 1, `next` 9800, `rnd` 1000), i.e. an index range plus filesort of the limited result.
   Hence plans must be captured under the limit, which the harness does (`plans/find_completed_before.summary.txt`: `sql_select_limit: 1000`, `access=range`).
   For a `GROUP BY ... ORDER BY MIN()` query the limit only reduces the sort output (`rnd` 43 -> 10), reads are unchanged.

## S1 comparison (bin/compare.py main -> opt, medians)

See `compare-S1.md` (full table). Extract: keys_strict 1.235 -> 0.616 ms, keys_nonstrict 1.415 -> 0.625, pages 0.487 -> 0.292, find_completed_before 11.7 -> 3.5, ids_completed_before 9.4 -> 0.49,
oldest_created 0.391 -> 0.325, oldest_due_strict 1.109 -> 0.437, delete_by_ids 9.2 -> 7.5; count_statuses (branch only) 0.119. S1 is only a harness check, not a result.

## Equivalence (item 3, keys_nonstrict ordering)

All queries PASS at the default batch of 10 (keys same order, `oldest_created` equal). The old rule declared a WARN whenever the limited lists differed and the full key sets were equal,
without checking the order. It now requires both limited lists to be sorted by `MIN(created_at)` of the key's due rows with identical created_at sequences; otherwise FAIL. In S1 exactly one pair of due keys
ties on `MIN(created_at)` (rank 71/72). Self-tests with `--queries-b` overrides (`overrides/`): `ORDER BY o.created_at DESC` -> FAIL (not hidden); tie permutation (`o.record_key ASC`) at batch 71/72 -> WARN with the
verified explanation (`equiv-selftest-*`). `find_*`/`ids_*` order differences inside equal `completed_at` are now verified too (`ids_*` look the column up by id); previously only asserted.
Conclusion: the WARN rule is sound after this change; in real runs a WARN means only created_at ties, and a FAIL means a genuine order or set difference.

## Repo-level sql.txt (mdb-opt, 2a2663de)

`mdb-opt/mariadb/S1/repo-jdbc/sql.txt` (head: `# repository field sql$delegate: io.namastack.outbox.query.MariaDbJdbcOutboxRecordQueries`, `# datasource: MariaDB 12.3.3 via MariaDB Connector/J 3.5.10`),
so dialect detection selects the MariaDB class. Statements show `[maxRows=10]` + `LIMIT ?` on keys, `[maxRows=0] SELECT MIN(created_at) ... AND completed_at IS NULL` for `oldest_created`.
`mdb-main/.../repo-jdbc/sql.txt` shows `rowLimitClause 'LIMIT :limit'`.

## Two-lane check

Lane 1 (`mdb-main`, label `S1-L1`) and lane 2 (`mdb-opt`, label `S1-L2`) ran `gen S1`, `bench --prepare`, `writecost`, and `equiv` at the same time (results `twolane/`, logs `twolane/lane1.log`, `lane2.log`): both finished rc=0,
28 equivalence verdicts each, separate containers/ports (13407/13507), separate snapshot markers (`build/snap-mariadb-L1.txt`, `-L2.txt`, each with `scenario=S1`), `env.txt` of each shows its own lane/container/port.
No shared files were written. (Timing interference is a calibration question, not measured here.)

## Observations / limits

- `ANALYZE TABLE` under `use_stat_tables=PREFERABLY_FOR_QUERIES` creates no engine-independent statistics (0 rows in `mysql.table_stats/column_stats/index_stats`).
- The branch's `NOT EXISTS` runs as a correlated subquery with `subquery_cache` (no anti-join) in 12.3.3.
- `pages_written` of the first writecost version stayed 0 (idle server defers flushing); fixed by the explicit flush around phases.
- Collation: server default `utf8mb4_uca1400_ai_ci`; `id` and `record_key` inherit it (see `env.txt`).
