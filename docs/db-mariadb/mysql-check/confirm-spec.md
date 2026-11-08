# MySQL confirm spec — `keys_*`: head form vs `NOT EXISTS` (2026-11-08)

Orchestrator spec, user request 2026-11-08. Follows `mysql-check/REPORT.md` (screen): on MySQL 9.2 the head form of `keys_strict` /
`keys_nonstrict` lost to `NOT EXISTS` at hot keys (S8-S5 `keys_strict` 31.8 s vs 227 ms). Stage **confirm + validate**. Brief
`briefs/06-experiment.md` (dialect `mysql`). Everything in `docs/db-mariadb/mysql-check/` (results `mysql-check/results-confirm/`).

## Runs

Same setup as the screen (`mysql-check/spec.md`): MySQL `mysql:9.2`, default settings (binary log on), code/catalog `mdb-opt2`
(`4037faa5`) MySQL catalog and DDL, UUIDv7 (`HARNESS_ID_VERSION=7` for every step, marker checked before each timed step).

| Run | `keys_strict`, `keys_nonstrict` | everything else |
|---|---|---|
| `my-head` | branch (head form) | branch |
| `my-ne` | `NOT EXISTS` (`mysql-check/forms-b/keys_strict.sql`, `keys_nonstrict.sql` only) | branch |

Overlay dir for `my-ne`: `mysql-check/forms-ne/` with only the two keys files.

## Labels

- Bench (groups `HOT,WRITE,MON`): `S1`, `S2`, `S8-S2`, `S16-S2`, `S4`, `S8-S4`, `S16-S4`, `S4-b100`, `S5`, `S8-S5`, `S6`, `S8-S6`,
  `S7`, `S8-S7`. Per-query limit max(2 x the other run's median, 60 s); `pages` on S4 / S4-b100 with its own `keys_strict` keys.
- Concurrency (solo cells): `S10-S2-p1-unthr`, `S10-S2-p4-unthr`, `S10-S4-p4`, `S10-S4-p4-unthr`. Verify first that `concurrency` runs
  on MySQL and uses the overlaid `keys_*` statements; fix minimally if not (README).
- No write-cost cells: the runs differ only in read statements.
- Arguments per label as in `experiments/final-run.sh`.

## Order

- Pass 1: `my-head`, `my-ne`; container stop/start; pass 2: `my-ne`, `my-head`, labels reversed. Per label gen once per pass,
  both runs back to back on the same snapshot. Lane 1 only, solo.
- Disk: run `PURGE BINARY LOGS BEFORE NOW()` after every cell and check free disk (stop at < 10 GB and report).
- Then a **2 GB buffer pool** pass (one pass, container recreated with `--innodb-buffer-pool-size=2G`, verify the variable):
  `S8-S2`, `S8-S5`, `S5`, `S10-S2-p4-unthr`, both runs. Restore the default afterwards.

## Equivalence

`my-ne` against `my-head` on S1, S2, S5, S7 (v7, `HARNESS_SAMPLE_ORDERED=1`), before pass 1.

## Report

`mysql-check/CONFIRM.md`: per label `my-head` vs `my-ne`, pass 1 / pass 2, change, `SPREAD>2x` flags, partial ownership first; plans
(`EXPLAIN ANALYZE`) and rows examined for `keys_*` on every label where they differ beyond noise; S10; 2 GB results; equivalence;
verdict by the ADR-0005 rule (P1 first); a recommendation for `MySqlJdbcOutboxRecordQueries` (keep head form / use `NOT EXISTS` / mixed).
