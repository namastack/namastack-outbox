# SQL optimization on MySQL — `outbox_record`

A self-contained, multi-agent prompt for finding the best MySQL indexes and queries of the `outbox_record` table, used through
`OutboxRecordRepository`, following ADR-0005. It uses the same scenarios, data, harness and decision rules as the PostgreSQL and
MariaDB studies, so the results can be compared.

- **Orchestrator:** Opus 5.5 (the session you start).
- **Sub-agents:** Sonnet 5.5 (spawned with `model: "sonnet"`).
- **Variants:** `my-main` (current `main`), `my-opt` (`feature/db-jdbc-optimization`, the potential end solution, measured
  first), experiment variants, and `my-final` (phase 5).
- **Database:** MySQL (`mysql:8.4` primary, `mysql:9.x` sensitivity run), JDBC only, MySQL Connector/J. JPA, MariaDB, other
  dialects and MongoDB are out of scope.
- **Priority 3 (retention/admin) is never run**, not even in the equivalence checks or the final comparison.
- **Outcome:** a comparison `main` vs branch vs final, and final MySQL DDL + `MySqlJdbcOutboxRecordQueries` ready to apply to
  `feature/db-jdbc-optimization`.

## How to run

Prerequisites: Docker running, JDK 25 installed (the orchestrator checks). Project builds use `harness/gradle/jdk25.init.gradle.kts`.
From the repository root:

```bash
claude --model opus
```

Then say:

> Execute `docs/db-mysql/00-orchestrator-prompt.md`.

To resume an interrupted run:

> Resume `docs/db-mysql/00-orchestrator-prompt.md` from `docs/db-mysql/STATE.md`.

## Isolation rule

`docs/db/` (old study), `docs/db-postgresql/` (PostgreSQL study) and `docs/db-mariadb/` (MariaDB study) must never be opened, listed,
grepped or summarized by any agent of this study (`briefs/00-isolation-rule.md`). Everything needed from the earlier studies is
already copied here: the scenario definitions (`02-scenarios.md`), the harness (`harness/`), and a rationale of the PostgreSQL
choices (`reference/postgres-rationale.md`), which only the PostgreSQL-transfer proposer reads. No MariaDB result, proposal or
experiment was copied.

## Layout

| Path | Written by | Purpose |
|---|---|---|
| `00-orchestrator-prompt.md` | — | Orchestrator prompt |
| `briefs/00-isolation-rule.md` | — | Rule pasted verbatim into every sub-agent prompt |
| `briefs/01-usage-map.md` | — | Usage map of the MySQL path, branch change list |
| `briefs/02-harness.md` | — | Harness port to MySQL, validation, calibration |
| `briefs/03-measure.md` | — | Run a measurement matrix |
| `briefs/04-propose-queries.md` | — | Query-form proposer (independent or PostgreSQL-transfer) |
| `briefs/05-propose-indexes.md` | — | Index-set proposer, fresh context |
| `briefs/06-experiment.md` | — | One experiment or screening round |
| `briefs/07-finalize.md` | — | Final candidate in code, tests, docs, MariaDB compatibility smoke |
| `reference/postgres-rationale.md` | — | Why each PostgreSQL index and query was chosen (hypotheses only) |
| `02-scenarios.md` | — | Scenarios (identical data to the other studies), labels, MySQL matrix, P3 exclusion |
| `harness/` | phase 2 | Copied harness (MariaDB study state) + one lane for this host; MySQL port in phase 2 |
| `STATE.md` | orchestrator | Progress ledger, decisions, questions for the user |
| `01-usage-map.md` | phase 1 | MySQL usage map, branch change list |
| `results/<variant>/mysql/<label>/` | phases 2–6 | Raw timings, plans, handlers, env |
| `03-main-vs-branch.md` | phase 3 | `main` vs `feature/db-jdbc-optimization` |
| `experiments/` | phase 4 | Proposals (`Q-*`, `I-*`), round specs, one report per experiment |
| `04-exploration.md` | phase 4 | Exploration summary, final set |
| `99-final-report.md` | phase 7 | Recommendations, final comparison |

## Host and parallelism

Bare metal Intel i7-8700K (6 cores / 12 threads, one L3), 15 GiB RAM, ~22 GB free disk: **one measuring lane**
(`harness/bin/lane.sh 1`). At most one measuring sub-agent at a time; non-measuring sub-agents (proposers, analysis) may run
alongside it; no Gradle builds while a timed step runs.
