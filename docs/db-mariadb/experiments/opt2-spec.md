# Spec — final candidate vs the moved branch (`mdb-opt2`, 2026-11-07)

Orchestrator spec. `origin/feature/db-jdbc-optimization` moved from `2a2663de` (`mdb-opt`, measured in phases 3-6) to `4037faa5`. Its
MariaDB code is now close to the confirm candidate `c-2`, but with the 4th index `(status, partition_no)` instead of
`(status, partition_no, created_at)`:
- DDL `(status, created_at, id, partition_no, next_retry_at, record_key)`, `(record_key, completed_at, created_at, id)`,
  `(status, next_retry_at, partition_no)`, `(status, partition_no)`;
- `keys_*` head forms without the outer `completed_at IS NULL`; `oldest_created`, `oldest_due_nonstrict` as `ORDER BY ... LIMIT`
  without `completed_at`; `oldest_due_strict` `NOT EXISTS` without the outer `completed_at IS NULL`.
Phase 7 must say what to apply to that branch, so the final candidate (`mdb-final`, `f31bc505`) is measured against it once.
Brief `briefs/06-experiment.md`, stage `screen` (one pass), but with S9, S10 and write cost.

## Variant

- Worktree `/home/azamojski/outbox-wt-mdb-opt2`, detached at `4037faa5` (`git worktree add --detach`). Build the variant jars and
  extract catalog `mdb-opt2` exactly as for `mdb-opt` (harness README, phase 2 procedure); `hashcheck`. Record the MariaDB catalog diff
  against `mdb-final` in the report (which statements differ).
- Ids UUIDv4 (never set `HARNESS_ID_VERSION`), comparable with phase 6.

## Equivalence

`mdb-opt2` against `mdb-final` on S1, S5, S7 (`HARNESS_SAMPLE_ORDERED=1`), before timing (lane 2 allowed while lane 1 is not timing).

## Runs

Solo cells, lane 1, lane 2 idle during every timed step. Per label gen once, `mdb-opt2` then `mdb-final` back to back.
- Bench (groups HOT,WRITE,MON): `S8-S2`, `S8-S4`, `S8-S5`, `S16-S2`, `S16-S4`, `S4`, `S5`, `S7`. `pages` on S4 with
  `--pages-keys-file experiments/r1/s4-pages-keys.txt`. Per-query limit 60 s.
- Write cost: `S2-wc`, `S4-wc`.
- Churn: `S9`, `--retention-max-s 2700`.
- Concurrency: `S10-S2-p1-unthr`, `S10-S2-p4-unthr`, `S10-S4-p4`.
Arguments for every label as in `experiments/final-run.sh`. Host-load rule and noise thresholds from `STATE.md`.

## Report

`experiments/opt2-vs-final.md`, brief 06 format: per label `mdb-opt2` vs `mdb-final` (and the phase-6 `mdb-main` value from
`results/final/SUMMARY.md` for reference), partial ownership first; plans for every query > 5 ms or differing by more than the
threshold; write cost and index sizes; S9 retention; S10; equivalence; verdict for `mdb-final` against `mdb-opt2` by the ADR-0005 rule.
