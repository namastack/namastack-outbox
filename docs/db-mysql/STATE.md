# State ledger (MySQL study)

Maintained by the orchestrator. Update after every step.

## Current phase

Phases 0, 1, 2 (port + S1 validation) done and reviewed; 4a (Q-merged, 66 forms PASS S1 smoke) and 4b (I-a, I-b) done. Calibration done (2026-11-06, `results/calibration/REPORT.md`; my-opt jars rebuilt, hashcheck 0/20004). Phase 3 not started (user starts it).

## Environment

- Host (2026-11-06, preparation session): bare metal (`systemd-detect-virt` = none), Intel Core i7-8700K, 6 cores / 12 threads
  (CPU n and n+6 are SMT siblings), one 12 MiB L3, 1 NUMA node, cgroup cpuset `0-11`, 15 GiB RAM, ~22 GB free on `/`,
  Docker 29.9.3, OpenJDK 25.0.4.1 (JDK 17 also installed), Python 3.14.4, `taskset` present, idle load ~0.2.
- Phase 0 (2026-11-06): image `mysql:8.4`, id/digest `sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902`,
  `mysqld Ver 8.4.13 for Linux on x86_64 (MySQL Community Server - GPL)`. JDK 25.0.4.1, Docker 29.9.3, Python 3.14.4, taskset ok,
  cgroup cpuset `0-11`, `lscpu -e` matches `lane.sh` (CPU n / n+6 siblings, one L3), 22 GB free on `/`, load 0.26 at idle,
  15 GiB RAM (12 GiB available). Harness `:sql:installDist` built; catalogs extracted (my-main 26 ids per dialect,
  `repository-fields`; my-opt 28 ids, `query-package`); `repo-bench hashcheck` 0 mismatches of 20004 keys for both.
- Lane (`harness/bin/lane.sh 1`): db `1,7,2,8` (cores 1–2), client `3,9` (core 3), MySQL port 13406; cores 0, 4, 5 free for the
  orchestrator, sub-agents, Gradle. Container limits `--cpus=4 --memory=4g`.

## Pinned variants

| Variant | Worktree | Sha | Notes |
|---|---|---|---|
| `my-main` | `../outbox-wt-my-main` | `f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f` | detached at local `main`. `origin/main` is one commit ahead (`00aaba01`, npm bump of the docs site only), not used |
| `my-opt` | `../outbox-wt-my-opt` | `270c42cf9b52d9ebb4445d0b4c11ccd8218a45f6` | re-pinned 2026-11-06 (user) to local `feature/db-jdbc-optimization`, 2 commits ahead of `origin` (`2a2663de`, used for phases 0-2 incl. calibration). Jars rebuilt, catalog re-extracted (mysql: keys_strict, keys_nonstrict, oldest_created, oldest_due_strict, oldest_due_nonstrict changed; DDL changed), hashcheck 0/20004 |

## Noise thresholds

From `results/calibration/REPORT.md` (cal-p1 vs cal-p2, p90 of |m1-m2|/min, both variants, S8-S2/S8-S4/S8-S5/S2-wc; max in brackets):
- HOT: **9 %** (max 15 %: S8-S4 pages my-opt, S8-S5 keys_strict my-main).
- MON >= 0.3 ms: **8 %** (max 151 %, S8-S2 count_new my-main 1.35 vs 0.54 ms; sub-3 ms MON queries are noisy, large scans 0-8 %).
- MON < 0.3 ms: **0.065 ms** absolute.
- WRITE bench timings: 104 % (max 156 %): single-row bench latency is not usable for verdicts; judge writes by writecost counters.
- Writecost per write: redo bytes **9 %**, pages/data written **13 %**, p50 latency **10 %** (delete_by_id up to 27 %, upd_fail 20 %).
  No row-lock waits or deadlocks.

## Background sensitivity

`results/calibration/cal-bg` (S8-S2, Gradle build pinned to CPUs 0,4,5 for the whole timed part, LOAD_MAX=100, `cal-bg/logs/NOTE.txt`):
HOT spread vs p1 5 %, vs p2 6-7 % (p1 vs p2 baseline 7 %); MON/WRITE within baseline. No effect above noise for pinned builds. The
"no Gradle during timed steps" rule stays as a precaution (unpinned load and heavy disk I/O not tested). Loadavg at step starts
1.0-2.9 in p1/p2, 4.4-5.9 in cal-bg.

## Branch change list

Re-based 2026-11-06 to main `f4446c8c` .. branch `270c42cf` (`01-usage-map.md` §7, correction notes in §1, §4, §6, §8). M1 ablation
reverts each P1/P2 item alone. No item removed, none added (no B-23).

| Item | Change at 270c42cf | Query ids | Prio | M1 |
|---|---|---|---|---|
| B-1 | add `idx_outbox_record_status_created_id (status, created_at, id, partition_no, next_retry_at, record_key)` (no `completed_at`) | keys_*, oldest_created, oldest_due_*, count_*; writes | P1,P2 | yes |
| B-2 | add `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)` | keys_* (subquery), pages, oldest_due_strict | P1,P2 | yes |
| B-3 | add `idx_outbox_record_status_retry_partition (status, next_retry_at, partition_no)` (no `completed_at`) | oldest_due_*; writes | P1,P2 | yes |
| B-4 | add `idx_outbox_record_status_partition (status, partition_no)` (no `completed_at`) | count_partitions, count_statuses | P2 | yes |
| B-5 | drop `(partition_no, status, next_retry_at)` | count_partitions, keys_*, oldest_* | P1,P2 | yes (re-add) |
| B-6 | drop `(status, next_retry_at)` | oldest_due_*, count_* (+P3) | P1,P2 | yes (re-add) |
| B-7 | drop `(status, created_at)` | keys_*, oldest_created (+P3) | P1,P2 | yes (re-add) |
| B-8 | drop `(record_key, created_at)` | keys_* subquery, pages (+P3) | P1 | yes (re-add) |
| B-9 | drop `(record_key, completed_at, created_at)` | keys_* subquery, pages | P1 | yes (re-add) |
| B-10 | `keys_strict` rewrite: `earlier` subquery with `completed_at IS NULL` and `created_at <=` range, `ORDER BY created_at, id LIMIT`; no outer `completed_at IS NULL` | keys_strict | P1 | yes |
| B-11 | `keys_nonstrict` `GROUP BY/MIN` → `NOT EXISTS` + `ORDER BY created_at, id LIMIT`; no outer `completed_at IS NULL` | keys_nonstrict | P1 | yes |
| B-12 | `pages` + `completed_at IS NULL` | pages | P1 | yes |
| B-13 | `oldest_created` `MIN` → `SELECT created_at ... ORDER BY created_at LIMIT :limit` + `withMaxRows(1)`; no `completed_at` | oldest_created | P2 | yes (and M2: MIN form) |
| B-14 | `oldest_due_strict` `MIN` → `ORDER BY next_retry_at LIMIT 1`; `completed_at IS NULL` only in the subquery | oldest_due_strict | P2 | yes (and M3) |
| B-15 | `oldest_due_nonstrict` `MIN` → `SELECT next_retry_at ... ORDER BY next_retry_at LIMIT`; no `completed_at` | oldest_due_nonstrict | P2 | yes (and M3) |
| B-16 | new grouped `countByStatuses` | count_statuses | P2 | no (n/a on main; measured as is) |
| B-17 | collector counts NEW+FAILED only | count_* | P2 | no (behaviour, inherited decision) |
| B-18 | `withMaxRows` on queries with `LIMIT` in SQL (keys_* batchSize, oldest_* 1) | keys_*, oldest_* | P1,P2 | folded into M9 |
| B-19, B-20 | dialect class layer, `status` parameter removed | — | — | no SQL effect, not measured |
| B-21, B-22 | retention `LIMIT`, `find_incomplete_by_key` + `completed_at IS NULL` | RET/ADMIN | P3 | listed, not measured |

Note: re-adding outer `completed_at IS NULL` (as at `2a2663de`) and the `completed_at`-bearing index variants are E-candidates for round 1.

## Experiments ledger

| Id | Hypothesis | Base | Stage | Verdict | Report |
|---|---|---|---|---|---|

## Decisions

- 2026-11-06 (orchestrator, phase 2 review; supersedes the phase 1 reading): Connector/J 9.7.0 `setMaxRows(n)` sends
  `SET sql_select_limit=n` / `SET sql_select_limit=DEFAULT` around every execution (two extra round trips), also when the SQL has
  `LIMIT` (`results/validation/VALIDATION.md` item 4, `general_log.txt`). `main` sends no `setMaxRows` for P1/P2 (LIMIT in SQL, catalog
  `maxrows: none`); the branch does for `keys_*` (batchSize) and `oldest_due_*` (1). The harness calls the real `setMaxRows` in the
  timed section, and captures plans under `SET SESSION sql_select_limit=n` (`--no-plan-select-limit` for M9). M9 is therefore a real
  experiment: drop `setMaxRows` where `LIMIT` is in SQL (B-18 and the `oldest_due_*` `withMaxRows(1)`), code item for phase 5 if it wins.
  `01-usage-map.md` §4 carries a correction note.
- 2026-11-06 (orchestrator): MariaDB servers reached through Connector/J (or `useMysqlMetadata=true`) get `MySqlJdbcOutboxRecordQueries`
  and `schema/mysql` (usage map §3). The final report states that performance results hold for MySQL only; phase 5 MariaDB smoke is
  correctness only. The `TIMESTAMP` auto-update caveat on old MariaDB (`explicit_defaults_for_timestamp=OFF`) is pre-existing, noted
  for the final report only.
- 2026-11-06 (orchestrator): schema ideas from the index proposers that change column types or add columns (hash of
  `record_key`, `status` as TINYINT, binary/shorter `id`, separate pending table) are ADR-0005 non-goals and are not tested;
  functional-index partial-index emulation is not tested (both proposers: no saving, NULL keys are indexed).
- 2026-11-06 (orchestrator): M1 reverts each index item alone (re-adding a dropped index counts as reverting its drop), plus one
  "whole branch DDL reverted" variant for context.

- Inherited decisions: section 0.4 of `00-orchestrator-prompt.md`.
- 2026-11-06 (user): Priority 3 (RET, ADMIN) is never run — no timing, plans, equivalence or repository-level calls, also not in the
  main-vs-branch and final comparisons.
- 2026-11-06 (preparation): same scenarios, data, generator and harness as the PostgreSQL/MariaDB studies; harness copied from the
  MariaDB study (state after its phase 2) without catalogs, results or experiments. S9 retention part to be replaced by a
  harness-internal delete (P3 statements of the variants are not used).
- 2026-11-06 (preparation): one measuring lane (single L3, 15 GiB RAM, ~22 GB disk); S2L not run (disk).
- 2026-11-06 (preparation): primary image `mysql:8.4` (LTS line), sensitivity run on the newest `mysql:9.x` in phase 6; binary log
  kept at the image default (on), purged by the harness after loads.

## Waiting for user

- 2026-11-06 (user): after calibration finishes, re-pin `my-opt` to local `feature/db-jdbc-optimization` `270c42cf` (2 commits ahead of `origin`, `2a2663de`; changes MySQL DDL and inherited SQL). Then re-extract the catalog, rebuild jars, hashcheck, update the branch change list. Calibration stays on 2a2663de (noise only).

- 2026-11-06 (user): stop after phase 2 (calibration + REPORT.md + my-opt rebuild/hashcheck). Phase 3 is started by the user later; do not start it without a new instruction.

(questions with options and a recommendation; see 0.10 of the orchestrator prompt)

## Running sub-agents

- Phase 2 (done): harness port, `harness/README.md` "MySQL port", `results/validation/VALIDATION.md` (S1 both variants: bench,
  plans, handlers, writecost, equiv 18 PASS + wrong-order self-test FAIL, concurrency/churn smoke, repo-level sql.txt shows
  `MySqlJdbcOutboxRecordQueries`, limitcheck 3.7 s, no P3 id in results). Known limits: single-table DML plans are estimates only
  (8.4 cannot EXPLAIN ANALYZE them); one producer reaches ~130 inserts/s, so `--rate 500` needs `--producers N`; `run-cells.sh`
  only syntax-checked.

- Phase 2 harness port + validation (brief 02, measuring, lane 1).
- (done) 4a independent proposer → `experiments/Q-indep/` (49 forms).
- (done) Q-merge → `experiments/Q-merged/` (74 SQL files incl. `-0` references; INDEX.md classes; none rejected; runner tokens
  `:recordKeysJson`, `:statusesJson`, `:recordKeysRows`, `:partitionsRows`, `:recordKeysSelects`, `/*EACH:..*/`, repeated `:partitions`
  still to be implemented in the harness before round 1). Caveat: key-table JOIN `pages` forms duplicate rows if the key list has
  collation-equal strings (keys come from the key query, so only case/accent variants of one key under `utf8mb4_0900_ai_ci`; note for review).
- (done) 4b I-a → `experiments/I-a/` (IA-0 branch .. IA-7; minimal IA-1 = A' (status,completed_at,created_at,id,partition_no,next_retry_at) + K;
  IA-5/IA-6 test the explicit `id` part (M4); IA-7 read-optimal).
- (done) 4b I-b → `experiments/I-b/` (IB-1 K+S minimal, IB-2 +R, IB-3 +C (status,partition_no,completed_at), IB-4 covering,
  IB-5/IB-6 partition-leading PA, IB-7 Kc=(record_key,created_at)+S).
- (done) Harness round tooling (README "MySQL port - round tooling"): run-cells.sh labels S16-*, S10-<scen>-p<n>[-unthr] (throttled
  `--rate 500 --producers 8` = 500 rec/s total; unthr `--rate 0 --producers 4`), load wait > 3, `restart:-:-`, `DRYRUN=1`;
  placeholder tokens in `NamedSql.expand`; `equiv --only`; `bin/form-overlay.sh`. S1 smoke: 66/66 merged forms PASS
  (`experiments/Q-merged/S1-smoke.md`).
- Calibration (brief 02 calibration part) → `results/calibration/{cal-p1,cal-p2,cal-bg}/`, `results/calibration/REPORT.md`. Measuring,
  lane 1. It was started by the orchestrator, which then ended its run while this was still going. On resume: if REPORT.md is missing,
  check `results/calibration/*/logs/` and `docker ps`, then finish the missing cells with `run-cells.sh` (finished cells are skipped).
  After the bg run, re-run `bin/variant-build.sh my-opt /home/zamo/git/outbox-wt-my-opt` and `bin/repo-bench my-opt hashcheck`.
  Resume 2026-11-06 ~15:20: pass 1 complete; pass 2 had only S8-S5 (my-opt S8-S4 prepare killed by the session close, orphan ALTER
  killed). Pass 2 rest re-launched by the orchestrator (background run-cells.sh). A Sonnet sub-agent (running) waits for it, then runs
  cal-bg (S8-S2 under a Gradle build pinned to CPUs 0,4,5), writes noise-*.md and REPORT.md, rebuilds my-opt jars + hashcheck. It does
  not edit STATE.md; the orchestrator copies thresholds here.
- (done) 4a PostgreSQL-transfer proposer → `experiments/Q-pgtransfer/` (45 forms; needs runner support for `ROW(?)` lists, JSON array, per-key UNION ALL branches).

## Next step

0. (2026-11-06, after the re-pin) Phase 4 inputs built on the `2a2663de` SQL/DDL must be rebased before round 1 (not before phase 3):
   `experiments/I-a/` (IA-0 = old branch DDL; IA-1..7 old index names), `experiments/I-b/` (IB-1..7, proposal baseline),
   `experiments/Q-merged/` (`QM-<id>-0` references of keys_strict, keys_nonstrict, oldest_created, oldest_due_strict,
   oldest_due_nonstrict; INDEX.md, FILES.txt; re-run S1 smoke), `Q-indep/` and `Q-pgtransfer/` forms for those 5 ids (dedupe against
   the new reference; hints naming `status_completed_*`). Old catalog kept at scratchpad copy only; `git show 2a2663de:...` reproduces it.
   Harness fix: `CatalogExtractor.kt` `oldest_created` query-package maxrows `none` → `one` (code calls `withMaxRows(1)`), catalog re-extracted.

1. (done 2026-11-06) Calibration complete, thresholds and background sensitivity above.
2. Phase 3 (waits for the user to start it): `cmp` run (brief 03), my-main vs my-opt, every label except S2L, two passes reversed with `restart:-:-` between, equiv on
   S1, S2, S5, S7; then write `03-main-vs-branch.md` (problem cells, useless branch items).
3. Round 1 spec `experiments/round1-spec.md`: M1-M9 + screen of the I-a/I-b sets (about 8-12 variants) with the Q-merged forms.
