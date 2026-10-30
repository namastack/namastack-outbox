# outbox_record index and query investigation (workspace)

Temporary workspace for the investigation described in `00-orchestration-prompt.md`: which indexes on
`outbox_record` to add, drop or change, and which JDBC queries to rewrite. Postgres 18 is the baseline.
Nothing here changes production code or schema files; everything runs in throwaway Docker containers.

## Files

| Path | What |
|---|---|
| `00-orchestration-prompt.md` | task spec |
| `STATE.md` | orchestrator ledger (phases, hypotheses, decisions) |
| `01-baseline.md` | baseline plans, timings, buffers, index usage, sizes, write cost, callers |
| `harness/start-postgres.sh` | start `outbox-db-<name>`, apply repo DDL (+ extra DDL), seed, `VACUUM ANALYZE` |
| `harness/bench-setup.sql` | `bench_now` table: the fixed "now" (`2026-01-01 00:00:00+00`) used by seeds and queries |
| `harness/seed-{A,B,C,D,E}.sql` | scenario seeds (section 5.2 of the prompt), deterministic, heap in `created_at` order |
| `harness/seed-edge.sql` | small edge-case seed for equivalence checks (ties, FAILED heads, mixed `next_retry_at`, empty partitions) |
| `harness/queries/*.sql` | one file per JDBC repository SQL, literal parameters, variants via psql vars |
| `harness/queries/_params.sql` | shared parameters (IN lists, sample ids, cutoffs, generic-plan placeholders) |
| `harness/run-explain.sh` | EXPLAIN ANALYZE runner: 1 warm-up + 3 runs, median, buffers -> `results/<label>/` |
| `harness/parse-explain.py` | parser used by `run-explain.sh` (summary TSV, trimmed raw plan) |
| `harness/compare-results.sh` | equivalence check of two query files (ordered md5), PASS/FAIL |
| `harness/workload-mix.sh` + `workload-mix.sql` | index-usage workload (100 polls, 10 scrapes, 1 cleanup) + `pg_stat_user_indexes` |
| `harness/write-cost.sh` | pgbench: 50k INSERT + 50k updateProcessingState, tps, WAL/op, index sizes |
| `harness/summarize.py` | markdown tables from `results/<label>/summary.tsv` (`--plans`, `--compare-modes`) |
| `harness/fix-costs-mode.py` | one-off: relabels `--costs` rows in summaries written before the costs mode got its own label |
| `harness/run-suite.sh` | everything above for one scenario and index set; removes the container |
| `harness/jdbc/` | pgjdbc probe showing what `withMaxRows` does on the server |
| `results/maxrows-probe-{A,B}.txt` | pgjdbc probe output |
| `results/suite-<S>.log` | console log of the baseline suites |
| `results/<label>/` | raw (trimmed) EXPLAIN output, `summary.tsv`, `sizes.txt`, `index-usage.txt`, `write-cost.txt` |

## Rerunning

Requirements: Docker, `postgres:18-alpine`, bash, python3, bc. Benchmarks must run sequentially
(one container at a time), otherwise timings are distorted.

```bash
cd docs/db/harness
# full baseline for one scenario (~5-10 min): results/base-A/
./run-suite.sh base-A A

# an index set under test: extra DDL applied after the repo schema, before seeding
# (indexes grow incrementally like in production) ...
./run-suite.sh H07-B B --ddl ../experiments/H07/indexes.sql
# ... or after seeding (faster, but indexes are freshly built and more compact)
./run-suite.sh H07-B B --post-seed-ddl ../experiments/H07/indexes.sql
# rewritten queries: copy harness/queries to a new dir, edit the SQL, keep the header lines
./run-suite.sh H08-B B -q ../experiments/H08/queries

# manual work on one container
./start-postgres.sh H07 B --ddl my-indexes.sql
./run-explain.sh -c outbox-db-H07 -s H07-B findRecordKeysInPartitions_filter@parts=256
./run-explain.sh -c outbox-db-H07 -s H07-B --generic findIncompleteRecordPages
./run-explain.sh -c outbox-db-H07 -s H07-B --set max_parallel_workers=0 --tag serial findRecordKeysInPartitions_filter
./compare-results.sh outbox-db-H07 queries/findRecordKeysInPartitions_filter.sql my/rewrite.sql --var parts=256
./workload-mix.sh outbox-db-H07 -o ../results/H07-B/index-usage.txt     # mutates data
./write-cost.sh outbox-db-H07 -o ../results/H07-B/write-cost.txt        # mutates data
docker rm -f -v outbox-db-H07
```

Running a query file by hand (prints the result, or the plan with `-v explain=...`):

```bash
docker exec outbox-db-H07 mkdir -p /harness/q && docker cp queries/. outbox-db-H07:/harness/q/
docker exec -it outbox-db-H07 psql -U postgres -v parts=256 -v keys=1000 \
  -v explain='EXPLAIN (ANALYZE, BUFFERS)' -f /harness/q/findIncompleteRecordPages.sql
```

## Query file format

```
-- method: <repository method>        -- sql: <property in JdbcOutboxRecordRepository>
-- class: hot | periodic | cleanup | admin
-- variants: parts=64 parts=256        (optional; run-explain runs each variant)
\ir _params.sql
\if :generic                           (optional; PREPARE with $n placeholders, used by --generic)
PREPARE q(...) AS ...; :explain EXECUTE q(...); DEALLOCATE q;
\else
:explain SELECT ... WHERE partition_no IN (:partlist) AND next_retry_at <= :'now_ts' ...;
\endif
```

Writes are wrapped in `BEGIN; ... ROLLBACK;`. psql variables: `parts` (64), `keys` (100), `status` (NEW),
`limit` (1000), `generic` (0). See `_params.sql` for all derived values.

## Measurement notes

- `EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING OFF, SUMMARY ON)`, 1 warm-up + 3 runs, median execution
  time; buffers are taken from the median run (root node, planning excluded).
- Container settings: data directory on tmpfs (`PG_TMPFS=6g`; the host disk was too small for 1M-row scenarios plus WAL), `shared_buffers=1GB` (the 1M-row table fits, so buffers are hits), `max_wal_size=1GB`, `work_mem=4MB`
  (default), `jit=off`, `--shm-size=1g`.
- `.withMaxRows(n)` is not visible to the planner, and pgjdbc's row-limited Execute makes the server run
  the plan without parallel workers. The `-serial` result files (`max_parallel_workers=0`) reproduce
  that execution. Details in `01-baseline.md`.
- The fixed `now` (`bench_now`) is used everywhere; `next_retry_at` "in the future" means `bench_now + 1h`.
- `workload-mix.sh` and `write-cost.sh` modify data: run them after the read benchmarks.

## Results

| Path | What |
|---|---|
| `99-final-report.md` | final report (recommendations, rejected ideas, per-query matrix, migration notes) |
| `90-cross-dialect.md` + `90-cross-dialect/` | MySQL, MariaDB, SQL Server, Oracle, JPA, MongoDB validation |
| `phase3-brief.md`, `phase3/` | Phase 3 brief, scripts and raw output |
| `experiments/H*.md` | hypotheses with results and decisions (ledger in `STATE.md`) |
| `queries/*.md` | per-query dossiers |
| `results/H60-matrix.md` | per-query matrix of the consolidated set |
