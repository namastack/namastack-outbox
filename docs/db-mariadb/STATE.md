# State ledger (MariaDB study)

Maintained by the orchestrator. Update after every step.

## Current phase

Phase 2 done; phase 3 waits for a user decision on host load. Phases 0-1 and phase 2 port/validation done 2026-11-05.

## Environment

- Host (2026-11-05): LXC container, AMD Ryzen 9 7900X, cgroup cpuset `0,2-5,7,9-11,13-15,17-18,21,23` (16 threads, 2 CCDs),
  56 GiB RAM, ~57 GB free on `/`, Docker 26.1.4. JDK: OpenJDK 25.0.4.1 only (Ubuntu package); worktree builds use `harness/gradle/jdk25.init.gradle.kts`.
- Lanes (`harness/bin/lane.sh`): lane 1 db `2,14,3,15` client `0,4,13` (CCD0); lane 2 db `9,21,11,23` client `7,10,18` (CCD1);
  `5,17` free for orchestrator/Gradle. Container limits `--cpus=4 --memory=4g`.
- MariaDB image `mariadb:12` = 12.3.3-MariaDB-ubu2404 on 2026-11-05 (smoke test): `innodb_buffer_pool_size` 128 MiB,
  `innodb_flush_method` O_DIRECT, `innodb_flush_log_at_trx_commit` 1, `log_bin` OFF, `eq_range_index_dive_limit` 200,
  `use_stat_tables` PREFERABLY_FOR_QUERIES, `optimizer_use_condition_selectivity` 4, `histogram_size` 254.

## Pinned variants

| Variant | Worktree | Sha | Notes |
|---|---|---|---|
| `mdb-main` | `../outbox-wt-mdb-main` | `f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f` | detached, = `origin/main` |
| `mdb-opt` | `../outbox-wt-mdb-opt` | `2a2663de2f2028790180399293981f7eca587ad1` | detached, `origin/feature/db-jdbc-optimization` (re-pinned 2026-11-05 on user request; was `a06a74c0`) |

## Noise thresholds

(phase 2 calibration)

| Query group | Threshold | Source |
|---|---|---|
| HOT | 11 % relative (max spread; p90 8 %) | `results/calibration/REPORT.md`, `noise-pass1-pass2.md` |
| MON | 10 % relative (p90 4 %); < 0.3 ms: 0.04 ms absolute | same |
| WRITE (rolled-back single statements) | 60 % relative and >= 0.2 ms absolute (p90 30 %) — timings weak; judge write cost by `writecost` | same |
| writecost redo bytes/write | 12 % (typ. 1-4 %) | `writecost-noise.md` |
| writecost latency p50 / p95 | 8 % / 25 % | same |
| writecost pages/write | 64 % (erratic; supporting evidence only) | same |

Measured with host load 11-13 from outside the container (another tenant); see Decisions.

## Lane decision

Calibration recommendation (b), adopted: only `gen`, `prepare` and equivalence in parallel; every timed cell serial in lane 1 with
lane 2 idle (interference: HOT +9-14 % / MON +70 % under gen+bench, HOT +43 % under S10). Snapshots identical across lanes
(`CHECKSUM TABLE`: S2 3370317100, S4 593475532, S5 3839299731).

## Branch change list

From `01-usage-map.md` §7 (branch `a06a74c0` vs main `f4446c8c`):

- **B-1** DDL: main's 5 secondary indexes replaced by 4; split for ablation:
  - **B-1a** `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`
  - **B-1b** `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`
  - **B-1c** `idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no)`
  - **B-1d** `idx_outbox_record_status_completed_partition (status, completed_at, partition_no)`
- **B-2** `keys_strict`: `o.completed_at IS NULL` + predecessor `NOT EXISTS` with `earlier.completed_at IS NULL` and `created_at <=` range.
- **B-3** `keys_nonstrict`: `GROUP BY record_key ORDER BY MIN(created_at)` -> first-due-per-key `NOT EXISTS`, `ORDER BY created_at, id`.
- **B-4** `pages`, `find_incomplete_by_key`: `AND completed_at IS NULL`.
- **B-5** `count_statuses` (grouped, NEW+FAILED default) replaces 3 `countByStatus` calls.
- **B-6** `oldest_created`: adds `completed_at IS NULL`; at `2a2663de` the form is `MIN(created_at)` again (no `ORDER BY`/`LIMIT`, no
  `setMaxRows`). Revert = main SQL. (At `a06a74c0` it was `ORDER BY created_at LIMIT 1`; that form is `Q-merged/oldest_created/oc-06-order-limit`.)
- **B-7** `oldest_due_strict` -> `ORDER BY next_retry_at LIMIT 1` with B-2 sub-select; `oldest_due_nonstrict` adds `completed_at IS NULL` (MIN).
- **B-8** `LIMIT :limit` on retention selects (P3) + `setMaxRows` on keys/oldest.
- **B-9** dialect machinery refactor (no MariaDB SQL change by itself; not an ablation item).

## Experiments ledger

| Id | Hypothesis | Base | Stage | Verdict | Report |
|---|---|---|---|---|---|

## Decisions

- Inherited decisions: section 0.3 of `00-orchestrator-prompt.md`.
- 2026-11-05 (preparation session): JDK 25 only on the host. Harness toolchain 25; worktree builds use
  `harness/gradle/jdk25.init.gradle.kts`. Pre-check done: harness builds, `hashcheck` passes, catalog extraction works, lane-1 MariaDB
  S1 smoke bench ran (results discarded). Phase 2 still validates the full harness.

- 2026-11-05 phase 0: JDK 25.0.4.1, Docker 26.1.4, python 3.13.13, taskset present, image `mariadb:12` id `2215e9af9663`
  (`mariadbd` 12.3.3-MariaDB-ubu2404), 56 GB free, cpuset unchanged (`lane.sh` table valid). Harness built (`:sql`, `:repo`
  installDist; `:repo` needs `build/variants/mdb-main.jars` first). Catalogs extracted: `mdb-main` 26 queries (repository-fields),
  `mdb-opt` 28 (query-package). `hashcheck` 0/20004 mismatches for both.

- Phase 1 (2026-11-05): MariaDB/MySQL/default classes render identical MariaDB SQL on the branch (MariaDB class has no overrides).
  Connector/J 3.5.10 against MariaDB >= 10.3 rewrites `setMaxRows(n)` to `SET STATEMENT SQL_SELECT_LIMIT=n FOR <sql>` (server-side limit);
  harness must use the MariaDB driver and plans for `setMaxRows` queries must be captured under the same `SQL_SELECT_LIMIT`.
- **Finding for the user (dialect detection):** a MariaDB server reached through MySQL Connector/J or with `useMysqlMetadata=true`
  reports product `MySQL` -> `MySqlJdbcOutboxRecordQueries` and `schema/mysql` DDL. Harmless today (identical SQL); once
  `MariaDbJdbcOutboxRecordQueries` gets overrides, such deployments will not get them. Report in phase 7.

- 2026-11-05 user: use the latest `origin/feature/db-jdbc-optimization`. Fast-forward `a06a74c0` -> `2a2663de` ("Change order by + limit
  to min for oldestCreatedAtByPartitionsStatus query"); MariaDB effect: only `oldest_created` (MIN, no limit). DDL unchanged. The phase 2 agent
  was told to re-pin the `mdb-opt` worktree at a safe point, re-extract the catalog, hashcheck and redo its mdb-opt validation steps.
  `Q-merged/oldest_created`: new `oc-00-base` = MIN form, previous base kept as `oc-06-order-limit`. Proposals (Q-*, I-*) were made
  against `a06a74c0`; all other SQL and DDL are identical, so they stay valid. M2 compares `oc-00-base` (MIN) vs `oc-06-order-limit`.

- Phase 2 port/validation done (`results/validation/VALIDATION.md`, README "MariaDB port"). Orchestrator review: OK. 5+20 reps; `main`
  setMaxRows -> server-side `SET STATEMENT SQL_SELECT_LIMIT` (general_log proof), plans captured under the same limit; branch binds `LIMIT ?`;
  `handlers.csv` outside timing; repo-level `sql.txt` shows `MariaDbJdbcOutboxRecordQueries`; two lanes isolated; equivalence tie rule
  now verified (FAIL on wrong order). Accepted limitation: buffer-pool priming scans each variant's own indexes (same procedure, different pages).
  Observations: no engine-independent stats are created; branch `NOT EXISTS` runs as dependent subquery + subquery cache (no anti-join);
  server collation `utf8mb4_uca1400_ai_ci` (inherited by `id`, `record_key`).

- Calibration (2026-11-05): `mdb-opt` S8-S2 `count_new`/`count_partitions`/`count_partition_single` flip between two equal-cost covering
  indexes across passes (<= 0.05 ms). Early signal: `mdb-main` S8-S4 `keys_nonstrict` ~25-27 s, `oldest_created` ~14-15 s; `mdb-opt` ~1 ms.
- **Host load issue:** load average 11-13 at nearly every step start and 12.7 with no container of ours running (38 % user CPU on the
  visible CPUs, no local process): another tenant of the Proxmox host shares the physical cores. The load > 4 rerun rule cannot be met.

## User decisions

## Running sub-agents

Nothing is running (waiting for a user decision on host load).
- Phase 4a query proposers started early (no measuring, no results access): `Q-indep` (independent), `Q-pgx` (PostgreSQL transfer).
  - `Q-pgx` done: 40 forms in `experiments/Q-pgx/` (all parse on 12.3.3, toy-table result check). Merge notes: `cs_incomplete`/`cp_incomplete`
    add `completed_at IS NULL` to queries whose status parameter may be COMPLETED -> contract-invalid unless restricted (check callers at merge);
    `pg_json_straight` needs a code change; `*_force`/`*_ignore` = index hints (user decision); `ks_window`/`kn_window` assume one partition per key.
  - `Q-indep` done: 41 forms in `experiments/Q-indep/` (3 fixed forms syntax-unverified).
  - Decision: `count_partitions`/`count_statuses` forms with `completed_at IS NULL` are class `status-conditional` (valid only if the code
    selects them when COMPLETED is not requested; `countRecordsByPartitions` is public and accepts any status, sole caller passes NEW).
  - Merge done: `experiments/Q-merged/` (60 forms + `*-00-base`/`*-00-main`, `INDEX.md`; classes ok 48, hint 13, code 6,
    status-conditional 6, mariadb-only 1; none invalid). Notes: `kn-08`/`kn-09` GROUP BY forms may differ at the LIMIT boundary (equivalence decides);
    some forms bind a collection twice (harness support to verify).
  - `I-A` done: sets A1 (i1+i2), A2 (n1 `(status,completed_at,created_at)` + n2 `(record_key,completed_at,created_at)`, implicit id),
    A3 (i1,i2,i3), A4 (d1 `(status,completed_at,next_retry_at,partition_no,created_at,id,record_key)` + i2), A5 (i1,i2,d1), A6 (= branch).
    i1..i4 = branch B-1a..B-1d. All DDLs apply on 12.3.3.
  - `I-B` done: B1 (i1,i2) = A1; B2 (i1,i2,i3) = A3; B3 (i1,i2,i4) = M1 "drop B-1c"; B4 (i2 + IP `(partition_no,status,completed_at,
    next_retry_at,created_at,id,record_key)`); B5 (i1,i2,IP); B6 = branch. Flagged (user decision, not in DDLs): generated `is_open`
    column, smaller `id` type (non-goal).
  - Distinct DDL candidates for round 1: A1/B1, A2, A3/B2, A4, A5, B3, B4, B5 (+ M1 drops of B-1a, B-1b, B-1d).
  Decision: proposals do not depend on measurements, so they run in parallel with phase 2; phase 3 still precedes any experiment.

## Next step

Review the harness (warmup, reps, identical data, setMaxRows/SQL_SELECT_LIMIT for main, LIMIT binding for opt, plans, handlers outside timing, repo-level sql.txt), then calibration.
