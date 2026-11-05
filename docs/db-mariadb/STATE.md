# State ledger (MariaDB study)

Maintained by the orchestrator. Update after every step.

## Current phase

Not started. Next: phase 0 (preflight) of `00-orchestrator-prompt.md`.

## Environment

- Host (2026-11-05): LXC container, AMD Ryzen 9 7900X, cgroup cpuset `0,2-5,7,9-11,13-15,17-18,21,23` (16 threads, 2 CCDs),
  56 GiB RAM, ~57 GB free on `/`, Docker 26.1.4. JDK: **not installed on 2026-11-05** (phase 0).
- Lanes (`harness/bin/lane.sh`): lane 1 db `2,14,3,15` client `0,4,13` (CCD0); lane 2 db `9,21,11,23` client `7,10,18` (CCD1);
  `5,17` free for orchestrator/Gradle. Container limits `--cpus=4 --memory=4g`.
- MariaDB image `mariadb:12` = 12.3.3-MariaDB-ubu2404 on 2026-11-05 (smoke test): `innodb_buffer_pool_size` 128 MiB,
  `innodb_flush_method` O_DIRECT, `innodb_flush_log_at_trx_commit` 1, `log_bin` OFF, `eq_range_index_dive_limit` 200,
  `use_stat_tables` PREFERABLY_FOR_QUERIES, `optimizer_use_condition_selectivity` 4, `histogram_size` 254.

## Pinned variants

| Variant | Worktree | Sha | Notes |
|---|---|---|---|
| `mdb-main` | `../outbox-wt-mdb-main` | (pin in phase 0; `f4446c8c` on 2026-11-05) | |
| `mdb-opt` | `../outbox-wt-mdb-opt` | (pin in phase 0; `a06a74c0` on 2026-11-05) | `origin/feature/db-jdbc-optimization` |

## Noise thresholds

(phase 2 calibration)

| Query group | Threshold | Source |
|---|---|---|

## Lane decision

(phase 2 calibration)

## Branch change list

(phase 1: `B-1`, `B-2`, …)

## Experiments ledger

| Id | Hypothesis | Base | Stage | Verdict | Report |
|---|---|---|---|---|---|

## Decisions

- Inherited decisions: section 0.3 of `00-orchestrator-prompt.md`.

## User decisions

## Running sub-agents

Nothing is running.

## Next step

Phase 0.
