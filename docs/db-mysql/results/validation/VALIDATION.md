# MySQL harness validation (brief 02, items 1-15)

Date 2026-11-06, lane 1 (db cpuset 1,7,2,8; client 3,9; port 13406), `mysql:8.4` = 8.4.13 (sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902), MySQL Connector/J 9.7.0, JDK 25.0.4.1,
scenario S1, variants `my-main` (f4446c8c) and `my-opt` (2a2663de). Container defaults (buffer pool 128 MiB, redo capacity 100 MiB, `log_bin=ON`, `sync_binlog=1`). All runs `--groups HOT,WRITE,MON`.
Gradle was never run while a timed step ran. Calibration was not run.

## Result summary

| Item | What | Result |
|---|---|---|
| 1 | build, smoke | `:sql:installDist :repo:installDist` ok; `repo-bench hashcheck` passed for both variants earlier; `bin/db.sh start mysql` starts `mysql:8.4` |
| 2 | lane | container `outbox-bench-mysql-L1`, volume `...-L1-data`, port 13406, `build/snap-mysql-L1.txt`, H2 dir `build/h2-L1` (all lane-suffixed in `Dialect`/`db.sh`); `run-cells.sh` `DIALECT` defaults to `mysql` (`bash -n` ok; not run end to end, the validation used the step commands it wraps) |
| 3 | P3 never runs | see "P3 absence proof" |
| 4 | setMaxRows on the wire | **Connector/J 9.7.0 sends `SET sql_select_limit=n` before and `SET sql_select_limit=DEFAULT` after** (see below) |
| 5 | plans | `.txt` (EXPLAIN ANALYZE), `.json`, `.trace.json` (reads), `.summary.txt` for all ids the variant has (16 for `my-main`, 18 for `my-opt`) (`my-*/mysql/S1/plans/`) |
| 6 | handlers | `handlers.csv` with the brief's columns, both variants |
| 7 | writecost | `--n 120`, both variants: redo bytes and LSN, pages/data written, buffer pool, lock waits, deadlocks, binlog bytes per phase and write, per-index sizes before/after (`writecost.csv`) |
| 8 | env/stats/warm-up | `env.txt` has every field of item 8; history list 9-80 at start (< 1000), priming note (5-6 index scans, 21-23 MiB of 128) |
| 9 | binary log | `RESET BINARY LOGS AND GTIDS` after gen/prepare/churn/concurrency/writecost (log lines `binary logs: ... now 0 MiB`) |
| 10 | S9 | `churn --inserts 5000`: identical final status counts for both variants (COMPLETED 8248, FAILED 121, NEW 180), 5000 oldest snapshot COMPLETED rows deleted by PK in batches of 1000 |
| 11 | S10 | 20 s, 4 pollers, `--rate 500`: processed/inserted per s, p50/p95 of keys/pages/update/insert, history list, lock waits, deadlocks, redo/pages/binlog deltas in `concurrency-p4-r500.txt` |
| 12 | repo-level | `my-opt/mysql/S1/repo-jdbc/{sql.txt,timings.csv}` (and `my-main`) |
| 13 | query limit | `bench limitcheck --dialect mysql --query-limit-s 3`: `limitcheck mysql: 3.7 s: stopped at limit 3 s` |
| 14 | noise.py / drivers | `noise.py` reads `timings.csv` trees unchanged (default dialect mysql); `run-cells.sh` defaults mysql, passes groups |
| 15 | end to end S1 | bench, plans, handlers, writecost, equiv (+ self-test), concurrency, churn, repo-level, general_log proof: all done, no failures |

## Item 4: what the server receives for setMaxRows (general_log)

`general_log.txt` (produced by `bench maxrowscheck --dialect mysql --schema run --scenario S1 --out ...`; `log_output=TABLE`, `general_log` switched on only for the test and off again, table truncated).
Excerpt (statement text cut; thread = the test connection):

```
case 1 stand-in without SQL LIMIT, setMaxRows(5):        Query  SET sql_select_limit=5
                                                         Query  SELECT id FROM outbox_record WHERE status = 'COMPLETED'
                                                         Query  SET sql_select_limit=DEFAULT
case 2 my-opt keys_strict (SQL LIMIT 10) + setMaxRows(10): Query  SET sql_select_limit=10
                                                         Query  SELECT o.record_key FROM outbox_record o WHERE ... LIMIT 10
                                                         Query  SET sql_select_limit=DEFAULT
case 3 my-opt oldest_due_strict + setMaxRows(1):           Query  SET sql_select_limit=1 / SELECT o.next_retry_at ... / SET sql_select_limit=DEFAULT
case 4 my-main keys_strict (SQL LIMIT 10), no setMaxRows:  Query  SELECT o.record_key ... LIMIT 10        (no SET at all)
```

- **This contradicts the phase 1 reading of the driver source** (`01-usage-map.md` §4, STATE.md decision of 2026-11-06): Connector/J 9.7.0 does apply the limit server-side through a session variable. The statements are separate
  `Query` commands (distinct event times, about 0.4-0.5 ms apart on the local host), i.e. two extra round trips per execution of a statement with `setMaxRows`, also when the SQL already contains `LIMIT`
  (case 2 and 3: the driver does not skip it). The `sql_select_limit` of the session is back to the default after each statement (`SELECT @@session.sql_select_limit` = 18446744073709551615 in `env.txt`).
- The timed section calls the real `setMaxRows` and therefore includes both `SET` statements and the server-side limit exactly as production sends them. `main` sends none for its P1/P2 queries (catalog `maxrows: none`, `LIMIT` in SQL).
- Consequences for the study: B-18 (`withMaxRows` on the key query) is not free (two extra statements per key query) and the limit can influence the plan; M9 can measure both effects. The `Handler_read_*` deltas in the log
  output (`Handler_read_next` 19 for case 2 vs 190 for case 4 on S1) show the limited execution reading fewer rows; they come from different SQL (`my-opt` vs `my-main`) and are no like-for-like measurement.
- **Plans therefore use `SET SESSION sql_select_limit = n`** for queries with `maxrows` != none (default; `--no-plan-select-limit` captures without it for M9). The `.summary.txt` of each plan states the applied limit
  (`my-opt` `keys_strict`: 10, `oldest_due_strict`: 1; `my-main` P1/P2: none).

## P3 absence proof (item 3)

- Kotlin: `Priority.groups` rejects any group outside HOT, WRITE, MON for `bench` and `equiv` (`--groups HOT,RET` and `--groups RET` end with `IllegalArgumentException: Priority 3 groups ... are disabled`, nothing prepared or run).
  `equiv` takes `--groups` (default HOT,WRITE,MON) and reads only the ids of those groups; the repository-level runner skips the P3 methods; `run-cells.sh` passes `--groups HOT,WRITE,MON` to `bench` and `equiv`
  and refuses a `BENCH_ARGS` naming RET/ADMIN.
- Evidence in this directory: `grep -rlE 'find_completed_before|find_failed_before|ids_completed_before|ids_failed_before|delete_by_ids|delete_by_status_failed|delete_by_key_status|find_pending_all|find_failed_all|find_incomplete_by_key|delete_completed_batch|delete_failed_batch' .`
  returns no file (no such id in any `timings.csv`, `plans/`, `handlers.csv`, equivalence report, `sql.txt`, log). `repo-jdbc/sql.txt` contains only P1/P2 sections (keys_*, pages, count_*, oldest_*, insert, upd_*, delete_by_id);
  its only `DELETE` is `DELETE FROM outbox_record WHERE id = ?`.

## Evidence

- Bench: `my-main/mysql/S1`, `my-opt/mysql/S1` (`timings.csv`, `handlers.csv`, `env.txt`, `plans/`), logs `bench-*.log`. Sanity: all 16 (`my-main`) / 18 (`my-opt`) P1/P2 ids returned rows or counts (keys 10, pages 10, counts 1-3).
  The two variants show the plans the usage map predicts (e.g. `my-opt` `keys_strict` uses `idx_outbox_record_status_completed_retry` + covering `idx_outbox_record_key_completed_created_id` lookup, a filesort over 150 candidate rows;
  `my-main` streams `idx_outbox_record_status_created` without filesort); these are validation observations, not study results (S1 is tiny).
- Writecost: `my-*/mysql/S1/writecost.csv`, `sizes-*-write.txt`, logs `writecost-*.log`. Update phases limited to 50 rows each (S1 has 150 due records). Binlog bytes at the end are equal for both variants (856 621).
- Equivalence (`HARNESS_SAMPLE_ORDERED=1`, P1/P2 only): `equiv/mysql/S1/my-main-vs-my-opt.md`: 18 ids PASS, 0 WARN/FAIL; exit code 0 (`equiv.log`). Self-test with `selftest-overlay/keys_strict.sql` (ORDER BY created_at DESC, id DESC):
  `selftest-results/equiv/mysql/S1/my-main-vs-my-opt.md` shows `keys_strict` **FAIL** ("limited lists differ beyond ties"), exit code 2 (`equiv-selftest.log`).
- Concurrency smoke: `my-*/mysql/S1-conc/concurrency-p4-r500.txt`. Both variants: 0 errors, 0 lock waits, 0 deadlocks; about 130 inserts/s (a single producer is bounded by durable commit latency, so the target rate 500 needs `--producers`).
- Churn smoke: `my-*/mysql/S1-churn/churn.txt` (sizes before/after, history list length, global deltas, `removed_by_retention ... 5000`).
- Repo level: `my-opt/mysql/S1/repo-jdbc/sql.txt` starts with `# repository field sql$delegate: io.namastack.outbox.query.MySqlJdbcOutboxRecordQueries` and
  `# datasource: MySQL 8.4.13 via MySQL Connector/J mysql-connector-j-9.7.0 ...`; `keys_strict`/`keys_nonstrict` are prepared with `[maxRows=10]`, `oldest_due_*` with `[maxRows=1]`. `my-main` shows
  `rowLimitClause 'LIMIT :limit'` and `maxRows=0` for its P1/P2 queries.

## Known limits / notes

- Single-table INSERT/UPDATE/DELETE: `EXPLAIN ANALYZE` returns `<not executable by iterator executor>` on 8.4; their plans are `EXPLAIN FORMAT=JSON` estimates (summary says so).
- S1 timings are tiny (< 10 ms) and are not study results; calibration must set the noise thresholds.
- The harness measures one connection (no pool) for the SQL-level runner, as in the inherited design.
- `run-cells.sh` was syntax-checked and its option plumbing reviewed, not executed end to end in this validation.
