# Brief 02 — Harness port to MySQL, validation, calibration

You are a sub-agent (Sonnet). Inputs: `docs/db-mysql/harness/` (copied from the MariaDB study harness, which already has generic
MySQL support from the PostgreSQL study and MariaDB-specific additions in `Maria.kt`), `docs/db-mysql/01-usage-map.md`,
`docs/db-mysql/02-scenarios.md`, the worktrees named by the orchestrator. Output: a working MySQL harness in
`docs/db-mysql/harness/` with the "MySQL port" section of `harness/README.md` listing every change.

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md` (pasted into your prompt). Do not reuse any script from `docs/db-sql/`,
`docs/db-mariadb/` or `docs/db/`; everything you may use is already copied into `docs/db-mysql/harness/`. You may not read the
reference file.

## Keep

Data generation (`Gen.kt`: seed, `T0`, MurmurHash3 partitions, second-truncated timestamps for MySQL), scenario definitions,
query ids, catalog extraction from the worktrees' real code (`bin/extract-catalog.sh`), timing rules (5 warmup + 20 reps,
slow-query caps), per-query limit, `--groups`, `--owned`, `--queries` overlays, ordered parameter sampling, `timings.csv` format,
`compare.py`, `run-cells.sh`, `noise.py`. Data must stay byte-identical to what the other studies generate for the same scenario
(apart from the timestamp truncation that already applies to MySQL). Keep the MariaDB code paths working (do not delete them); add
MySQL equivalents next to them (e.g. a `MySql.kt` modelled on `Maria.kt`, or one InnoDB helper with a dialect switch).

## Add / fix

1. **Build and smoke.** `./gradlew --console=plain -q :sql:installDist :repo:installDist` with JDK 25 (worktree builds use
   `gradle/jdk25.init.gradle.kts`; `bin/variant-build.sh` already does that). `bin/repo-bench <v> hashcheck` passes against both
   worktrees. `bin/db.sh start mysql` uses `HARNESS_MYSQL_IMAGE` (default `mysql:8.4`).
2. **Lane.** One lane on this host (`bin/lane.sh 1`, MySQL port 13406). Check that the lane's container, port, volume, snapshot
   marker, results and H2 files are lane-scoped as on MariaDB, and that `run-cells.sh` defaults to `DIALECT=mysql`.
3. **Priority 3 never runs.** `bench` is always called with `--groups HOT,WRITE,MON` by the drivers. Add `--groups` (default
   `HOT,WRITE,MON` in this copy) to `equiv` so it reads only P1/P2 query ids, and make the repository-level runner skip P3 methods
   (`find*Records`, `delete*Records`, `deleteByIds`, `deleteByStatus`, `deleteByRecordKeyAndStatus`,
   `findIncompleteRecordsByRecordKey`, extra ids `delete_completed_batch`/`delete_failed_batch`). Make `run-cells.sh` pass the
   groups to every `bench`/`equiv` call. Prove it: no RET/ADMIN id appears in any `timings.csv`, `plans/`, equivalence report or
   `repo-jdbc/sql.txt` of the validation run.
4. **setMaxRows fidelity (MySQL Connector/J).** The SQL runner uses MySQL Connector/J (`jdbc:mysql:`, driver defaults: client-side
   prepared statements, no statement cache; keep `rewriteBatchedStatements=true` only on the `gen` load connection, not on the timed
   connection) and calls the real `PreparedStatement.setMaxRows(n)` for catalog entries whose `maxrows` is not `none`. Verify with the
   server's `general_log` what the server receives for a `main` query with `setMaxRows` and for a branch query with `LIMIT ?` and
   `setMaxRows` (expected: `SET SQL_SELECT_LIMIT=n` before and `SET SQL_SELECT_LIMIT=DEFAULT` after; check whether the driver skips it
   when the SQL has `LIMIT`). The timed section must include exactly what production sends (extra round trips included). Write the
   evidence into `results/validation/VALIDATION.md`.
5. **MySQL plans.** For every timed query with the first parameter set: `EXPLAIN ANALYZE` (tree with actual rows, loops, time;
   writes inside a rolled-back transaction) → `plans/<id>.txt`; `EXPLAIN FORMAT=JSON` (estimates, `used_key_parts`, `key_length`,
   `attached_condition`, `using_index`, `using_filesort`, `rows_examined_per_scan`, `filtered`, `cost_info`) → `plans/<id>.json`; the
   optimizer trace (`SET optimizer_trace='enabled=on'`, `information_schema.OPTIMIZER_TRACE`, for reads only, truncated to the
   `considered_execution_plans`/`rows_estimation`/`attaching_conditions` parts if large) → `plans/<id>.trace.json`. Plans of
   queries with `maxrows` != none are captured under the same `SET SESSION sql_select_limit = n` (reset afterwards), because the limit
   can change the plan. Non-executing `EXPLAIN FORMAT=TREE` + JSON for queries > 30 s or stopped by the per-query limit. Add
   `plans/<id>.summary.txt` (per table: access type, key, key parts used, rows examined vs returned from `EXPLAIN ANALYZE`,
   filesort/temporary yes/no, join/semijoin/antijoin strategy such as `antijoin`, `FirstMatch`, `Materialize`, `LooseScan`,
   hash join, index skip scan, index merge, the applied `sql_select_limit`) so reports can quote plans without reading JSON.
6. **Rows examined.** Per timed query, session `Handler_read_%` deltas (`SHOW SESSION STATUS` before/after one extra execution, cost
   of the two `SHOW STATUS` calls subtracted) into `handlers.csv` (`query_id,Handler_read_key,Handler_read_next,Handler_read_prev,
   Handler_read_first,Handler_read_last,Handler_read_rnd,Handler_read_rnd_next,bp_read_requests,bp_reads`). Not inside the timed
   section.
7. **Write cost on InnoDB** (`writecost`): per phase (`insert_1thread`, `insert_4threads`, `upd_complete_committed`,
   `upd_retry_committed`, `upd_fail_committed`, `delete_by_id_committed`) the global deltas of `Innodb_os_log_written` (redo bytes; on
   MySQL ≥ 8.0.30 also `Innodb_redo_log_current_lsn`), `Innodb_pages_written`, `Innodb_data_written`,
   `Innodb_buffer_pool_read_requests`, `Innodb_buffer_pool_reads`, `Innodb_row_lock_waits`, `Innodb_row_lock_time`, deadlocks
   (`information_schema.INNODB_METRICS` `lock_deadlocks`), and binary-log bytes written (sum of `SHOW BINARY LOGS` sizes; informational,
   expected equal across variants), per write; latency p50/p95 and throughput; dirty pages flushed before/after each phase
   (`innodb_max_dirty_pages_pct=0` then restore, as on MariaDB) so pages written belong to the phase; table and **per-index** sizes
   before/after (`ANALYZE TABLE` + `mysql.innodb_index_stats` `stat_name='size'`, tablespace `FILE_SIZE`/`ALLOCATED_SIZE` from
   `information_schema.INNODB_TABLESPACES`) and index count. The counters are global: nothing else may use the lane's server.
8. **Statistics, purge and warm-up.** `prepare` ends with `ANALYZE TABLE outbox_record` (persistent stats, no histograms; histograms
   are an observation-only experiment). Record in `env.txt`: server version, image tag and id, `innodb_stats_persistent`,
   `innodb_stats_persistent_sample_pages`, `innodb_stats_auto_recalc`, `eq_range_index_dive_limit`, `range_optimizer_max_mem_size`,
   `optimizer_switch`, `optimizer_search_depth`, `innodb_buffer_pool_size`, `innodb_flush_log_at_trx_commit`, `innodb_flush_method`,
   `innodb_adaptive_hash_index`, `innodb_change_buffering`, `innodb_redo_log_capacity`, `log_bin`, `binlog_format`,
   `binlog_row_image`, `sync_binlog`, `transaction_isolation`, `character_set_server`, `collation_server`, `sql_mode`, the actual
   charset/collation of `id` and `record_key` (`information_schema.COLUMNS`), any histograms present
   (`information_schema.COLUMN_STATISTICS`). Before the first timed query wait until the history list length
   (`information_schema.INNODB_METRICS` `trx_rseg_history_len`; MySQL has no `Innodb_history_list_length` status) is < 1000 (max
   120 s) and record it. Prime the buffer pool identically for every variant (port the MariaDB priming: `FORCE INDEX` count per index,
   smallest first, until 80 % of the buffer pool) and document it.
9. **Binary log and disk.** Image defaults stay (`log_bin=ON`), but purge binary logs after `gen`, `prepare`, `churn`, `concurrency`
   and `writecost` (`PURGE BINARY LOGS BEFORE NOW()` or `RESET BINARY LOGS AND GTIDS`, whichever the version supports; never inside a
   timed section) so the ~22 GB free disk is not filled. `run-cells.sh` keeps its `MINFREE_GB` stop (default 8).
10. **S9 on MySQL.** `churn` keeps its insert/process part (catalog `insert`, `upd_*`, `delete_by_id` of the variant, all P1) but
    replaces the retention part, which uses the variant's P3 statements (`ids_completed_before`, `delete_by_ids`), with a
    **harness-internal** delete that is identical for every variant: pick the ~500 000 oldest COMPLETED ids with fixed SQL (e.g. from the
    snapshot or one ordered scan), delete them by primary key in batches of 1000, untimed. Record sizes, history list length and global
    counter deltas before/after; no manual `ANALYZE`/`OPTIMIZE`. `run-cells.sh churn:*:S9` then runs the P1/P2 bench on the churned table.
11. **S10 on MySQL.** `concurrency` works on MySQL (pollers own disjoint partitions, producer at `--rate 500` and `0`); report
    processed rec/s, inserted rec/s, p50/p95 of keys, pages, update, insert; history list length, `Innodb_row_lock_waits`, deadlocks,
    redo/pages deltas.
12. **Repository-level runner, JDBC on MySQL.** `RepoBench --dialect mysql` (JDBC engine only; Hikari on the lane's port, MySQL
    Connector/J, driver defaults) so the real `JdbcOutboxPersistenceFactory` of a worktree runs against MySQL. Its `sql.txt` must show
    the product the data source reports, the query class the repository resolved (`MySqlJdbcOutboxRecordQueries` on the branch) and the
    statements of P1/P2 methods with their `setMaxRows`. Timings optional.
13. **Per-query limit.** Check `--query-limit-s` / `--limit-from` with Connector/J (`Statement.cancel()` = `KILL QUERY`):
    `bench limitcheck --dialect mysql --query-limit-s 3`.
14. **Noise script, drivers.** `noise.py` and `run-cells.sh` work with `--dialect mysql` / `DIALECT=mysql` (defaults already switched).
15. **Validation.** S1 end to end for `my-main` and `my-opt` in lane 1: bench (`--groups HOT,WRITE,MON`), plans, handlers, writecost
    (`--n 120`), equivalence (P1/P2 only, `HARNESS_SAMPLE_ORDERED=1`, plus a self-test: an overlay with a deliberately wrong key order
    must FAIL), a 20 s concurrency smoke, a short churn smoke (`--inserts 5000`), repo-level JDBC `sql.txt` for `my-opt`, the
    `general_log` proof of item 4, the P3 absence proof of item 3. Write `results/validation/VALIDATION.md`.

## Calibration (after validation, only when the orchestrator asks)

Run `docs/db-mysql/briefs/03-measure.md` style cells in lane 1 (the only lane), results under `results/calibration/`:

1. **Noise:** `my-main` and `my-opt` on `S8-S2`, `S8-S4`, `S8-S5`, `S2-wc`, twice with a container restart between the passes
   (pass 2 in reverse variant order).
2. **Background sensitivity** (replaces the lane interference test of a two-lane host): the same `S8-S2` cells once while a Gradle
   build of a worktree runs on the free CPUs (cores 0, 4, 5). If timings move above the noise threshold, the rule "no Gradle or other
   heavy work while a timed step runs" is mandatory; record the result.
3. Write `results/calibration/REPORT.md`: noise thresholds per group (relative and absolute, groups as in `02-scenarios.md`), the
   background sensitivity, and the host load observed at step starts (`/proc/loadavg`).

Return a summary of ≤300 words: what changed, what was validated, known limits, and (if run) the calibration result.
