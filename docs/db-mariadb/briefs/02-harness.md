# Brief 02 — Harness port to MariaDB, validation, calibration

You are a sub-agent (Sonnet). Inputs: `docs/db-mariadb/harness/` (copied from the PostgreSQL study, generic parts only),
`docs/db-mariadb/01-usage-map.md`, `docs/db-mariadb/02-scenarios.md`, the worktrees named by the orchestrator. Output: a working
harness in `docs/db-mariadb/harness/` with an updated `README.md` ("MariaDB port" section listing every change).

## Isolation rule

See `docs/db-mariadb/briefs/00-isolation-rule.md` (pasted into your prompt). Do not reuse any script from `docs/db-sql/` or `docs/db/`;
everything you may use is already copied into `docs/db-mariadb/harness/`. You may not read the reference file.

## Keep

Data generation (`Gen.kt`: seed, `T0`, MurmurHash3 partitions, second-truncated timestamps for MariaDB), scenario definitions,
query ids, catalog extraction from the worktrees' real code (`bin/extract-catalog.sh`), the `setMaxRows` reproduction for `main`,
timing rules (5 warmup + 20 reps, slow-query caps), per-query limit, `--groups`, `--owned`, `--queries` overlays, ordered parameter
sampling, `timings.csv` format, `compare.py`. Data must stay byte-identical to what the PostgreSQL study generated for the same
scenario (apart from the timestamp truncation that already applies to MariaDB).

## Add / fix

1. **Build and smoke.** `./gradlew :sql:installDist :repo:installDist` with JDK 25 (worktree builds use `gradle/jdk25.init.gradle.kts`). `bin/repo-bench <v> hashcheck` passes against
   both worktrees.
2. **Lanes.** Already implemented (`bin/lane.sh`, `HARNESS_LANE`, `HARNESS_CPUSET`, `HARNESS_CLIENT_CPUSET`,
   `HARNESS_EXTRA_ARGS`, lane fields in `env.txt`). Verify two lanes run `gen` + `bench` on S1 at the same time without touching each
   other's container, snapshot marker, results or H2 files. Fix what breaks.
3. **MariaDB plans.** `ANALYZE FORMAT=JSON` (`r_rows`, `r_filtered`, `r_loops`, `r_total_time_ms`, chosen index, `using_filesort`,
   `using_index_condition`, `rowid_filter`, semijoin/anti-join strategy) captured for every query with the first parameter set;
   non-executing `EXPLAIN FORMAT=JSON` for capped queries. Add a short `plans/<id>.summary.txt` (access type, key, key parts used,
   rows examined vs returned, filesort yes/no) so reports can quote plans without reading JSON.
4. **Rows examined.** Per timed query, session `Handler_read_%` deltas (`SHOW SESSION STATUS` before/after one extra execution)
   into `handlers.csv` (`query_id,Handler_read_key,Handler_read_next,Handler_read_prev,Handler_read_first,Handler_read_rnd,
   Handler_read_rnd_next`). Not inside the timed section.
5. **Write cost on InnoDB** (`writecost`, replacing the PostgreSQL WAL part for MariaDB): per phase (`insert_1thread`,
   `insert_4threads`, `upd_complete_committed`, and add `upd_retry_committed`, `upd_fail_committed`, `delete_by_id_committed`)
   the global deltas of `Innodb_os_log_written` (redo bytes), `Innodb_pages_written`, `Innodb_data_written`,
   `Innodb_buffer_pool_read_requests`, `Innodb_buffer_pool_reads`, per write; latency p50/p95 and throughput as now; table and
   **per-index** sizes before/after (from `mysql.innodb_index_stats` after a plain `ANALYZE TABLE`, plus the tablespace
   file size from `information_schema` if available) and index count. Make sure the counters are global and that no other lane shares the server (one server per lane).
6. **Statistics after load.** `prepare` runs `ANALYZE TABLE outbox_record` (InnoDB persistent stats). Record in `env.txt`:
   `innodb_stats_persistent`, `innodb_stats_persistent_sample_pages`, `use_stat_tables`, `optimizer_use_condition_selectivity`,
   `eq_range_index_dive_limit`, `optimizer_switch`, `innodb_buffer_pool_size`, `innodb_flush_log_at_trx_commit`,
   `innodb_flush_method`, `log_bin`, server version. Wait until `Innodb_history_list_length` is low (< 1000, max 120 s) before the
   first timed query, and record its value. Make the bench start with a warm buffer pool the same way for every variant (e.g. the
   warmup reps; document what you do).
7. **S9 on MariaDB.** `churn` records sizes and `Innodb_history_list_length` before/after, then runs the P1/P2 bench on the churned
   table (`--label S9`), no manual `ANALYZE`/`OPTIMIZE`.
8. **S10 on MariaDB.** `concurrency` works on MariaDB (pollers own disjoint partitions, producer at `--rate 500` and `0`); report
   processed rec/s, inserted rec/s, p50/p95 of keys, pages, update, insert; deadlock/lock-wait counts (`Innodb_row_lock_waits`,
   `Innodb_deadlocks` if available) as deltas.
9. **Repository-level runner, JDBC on MariaDB.** Extend `RepoBench` with `--dialect mariadb` (JDBC engine only; Hikari on the lane's
   port, MariaDB Connector/J) so the real `JdbcOutboxPersistenceFactory` of a worktree runs against MariaDB. Its `sql.txt` must show
   which statements the branch really sends, which proves that dialect detection picks the MariaDB class. Timings optional.
10. **Drivers.** Write `bin/run-cells.sh`: resumable driver modelled on `bin/_template-serial-driver.sh`, for one lane, taking a list of
    cells (`<variant>:<label>` plus kinds `bench`, `writecost`, `concurrency`, `churn`, `equiv`), skipping finished cells
    (`REDO=1` repeats), logging to `results/logs/<run-name>-L<lane>.log`, writing failures to `results/logs/<run-name>-failures.txt`,
    stopping below `MINFREE_GB` (default 8), running `gen` only when the lane's snapshot holds a different scenario, recording host
    `/proc/loadavg` before each step. Variants are given as `name=worktree[,ddl=FILE][,queries=DIR][,catalog=NAME]`. A pass order
    option (`ORDER=forward|reverse`) runs the variants of a cell in the given order.
11. **Noise script.** Generalize `bin/noise.py` to compare any two result trees (pass 1 vs pass 2), per group, and output the
    p90/max relative spread and the p90 absolute difference for queries below 0.3 ms.
12. **Validation.** S1 end to end for `mdb-main` and `mdb-opt` in lane 1: bench, plans, handlers, writecost, equivalence, a 20 s
    concurrency smoke, repo-level JDBC `sql.txt` for `mdb-opt`. Then S1 in lanes 1 and 2 at the same time.

## Calibration (after validation, only when the orchestrator asks)

Run `docs/db-mariadb/briefs/03-measure.md` style cells, results under `results/calibration/`:

1. **Noise:** `mdb-main` and `mdb-opt` on `S8-S2`, `S8-S4`, `S8-S5`, `S2-wc` in lane 1 solo, twice with a container restart between
   the passes (pass 2 in reverse variant order).
2. **Lane interference:** the same `S8-S2` and `S8-S4` cells in lane 1 while lane 2 runs a heavy load (gen + `S4` bench of
   `mdb-main`), and once while lane 2 runs `S10` concurrency. Compare to the solo medians.
3. Write `results/calibration/REPORT.md`: noise thresholds per group (relative and absolute, as in `02-scenarios.md` groups) and the
   interference per group, with a recommendation: (a) timings in 2 lanes allowed, (b) only `gen`/`prepare`/equivalence in parallel,
   timings serial, or (c) no parallelism.

Return a summary of ≤300 words: what changed, what was validated, known limits, and (if run) the calibration recommendation.
