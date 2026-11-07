# M5 follow-up spec — `pages` forms under S10 (2026-11-07)

Orchestrator spec, user request 2026-11-07. Closes the gap that round 1b part 3 (`experiments/r1b-forms.md` section 5) timed the
`pages` forms only alone (S8-S5, S5, S4-b100), not under S10 as the orchestrator prompt (M5) asks. Brief `briefs/06-experiment.md`.
Start only after phase 6 (`results/final/`) has finished and its driver is no longer running. Ids UUIDv4 (never set `HARNESS_ID_VERSION`).

## Runs

All on the final candidate: catalog variant `mdb-final` (DDL and SQL of `exp/mdb-final`), only `pages` replaced.

| Run | `pages` |
|---|---|
| `m5c-in` | as in `mdb-final` (`IN (:recordKeys)`) |
| `m5c-json` | `experiments/Q-merged/pages/pa-06-json-table.sql` |
| `m5c-values` | `experiments/Q-merged/pages/pa-08-values-cte.sql` |

Overlay: one directory per run with a single `pages.sql` (catalog header copied from `harness/catalog/mdb-final/mariadb/pages.sql`,
SQL from the Q-merged form), passed with `--queries DIR`. First verify that the `concurrency` command applies `--queries` to the
statement its pollers run for `pages`; if it does not, extend the harness minimally and note it in the harness README.

## Labels and order

- `S10-S2-p4` and `S10-S4-p4` (4 pollers, 60 s, rate 500), arguments as in `experiments/final-run.sh`.
- Per label: gen once; order `m5c-in`, `m5c-json`, `m5c-values`, `m5c-in` (drift check). Solo cells, lane 2 idle.
- Host-load rule from `STATE.md`.

## Equivalence

`m5c-json` and `m5c-values` against `mdb-final` on S1 and S5 (`HARNESS_SAMPLE_ORDERED=1`), before the timed runs. A FAIL makes the run
`invalid`.

## Report

`experiments/m5-s10.md`, brief 06 format, short: per label processed rec/s, `pages` p50/p95 if the concurrency output reports them,
the drift between the two `m5c-in` runs, equivalence, verdict per form (`win` / `no difference` / `loss`, noise threshold for S10 from
`STATE.md`), and whether a derived key table is worth a code change.
