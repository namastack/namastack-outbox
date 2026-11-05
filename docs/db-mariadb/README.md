# SQL optimization on MariaDB — `outbox_record`

A self-contained, multi-agent prompt for finding the best MariaDB indexes and queries of the `outbox_record` table, used through
`OutboxRecordRepository`, following ADR-0005. It uses the same scenarios, data and decision rules as the PostgreSQL study.

- **Orchestrator:** Opus 5.5 (the session you start).
- **Sub-agents:** Sonnet 5.5 (spawned with `model: "sonnet"`).
- **Variants:** `mdb-main` (current `main`), `mdb-opt` (`feature/db-jdbc-optimization`, the potential end solution, measured
  first), experiment variants, and `mdb-final` (phase 5).
- **Database:** MariaDB (`mariadb:12`), JDBC only. JPA, MySQL, other dialects and MongoDB are out of scope.
- **Outcome:** a comparison `main` vs branch vs final, and final MariaDB DDL + `MariaDbJdbcOutboxRecordQueries` ready to apply to
  `feature/db-jdbc-optimization`.

## How to run

Prerequisites: Docker running, JDK 25 installed (the orchestrator checks and asks otherwise). No JDK 17 is needed: project builds use
`harness/gradle/jdk25.init.gradle.kts`. From the repository root:

```bash
claude --model opus
```

Then say:

> Execute `docs/db-mariadb/00-orchestrator-prompt.md`.

To resume an interrupted run:

> Resume `docs/db-mariadb/00-orchestrator-prompt.md` from `docs/db-mariadb/STATE.md`.

## Isolation rule

`docs/db/` (old study) and `docs/db-sql/` (PostgreSQL study, which also has earlier MariaDB numbers) must never be opened, listed,
grepped or summarized by any agent of this study (`briefs/00-isolation-rule.md`). Everything needed from the PostgreSQL study is
already copied here: the scenario definitions (`02-scenarios.md`), the generic harness (`harness/`), and a rationale of the
PostgreSQL choices (`reference/postgres-rationale.md`), which only the PostgreSQL-transfer proposer reads.

## Layout

| Path | Written by | Purpose |
|---|---|---|
| `00-orchestrator-prompt.md` | — | Orchestrator prompt |
| `briefs/00-isolation-rule.md` | — | Rule pasted verbatim into every sub-agent prompt |
| `briefs/01-usage-map.md` | — | Usage map of the MariaDB path, branch change list |
| `briefs/02-harness.md` | — | Harness port, lanes, InnoDB write cost, validation, calibration |
| `briefs/03-measure.md` | — | Run a measurement matrix |
| `briefs/04-propose-queries.md` | — | Query-form proposer (independent or PostgreSQL-transfer) |
| `briefs/05-propose-indexes.md` | — | Index-set proposer, fresh context |
| `briefs/06-experiment.md` | — | One experiment or screening round |
| `briefs/07-finalize.md` | — | Final candidate in code, tests, docs |
| `reference/postgres-rationale.md` | — | Why each PostgreSQL index and query was chosen (hypotheses only) |
| `02-scenarios.md` | — | Scenarios (identical data to the PostgreSQL study), labels, MariaDB matrix |
| `harness/` | phase 2 | Copied generic harness + lanes (`bin/lane.sh`); MariaDB additions in phase 2 |
| `STATE.md` | orchestrator | Progress ledger, decisions |
| `01-usage-map.md` | phase 1 | MariaDB usage map, branch change list |
| `results/<variant>/mariadb/<label>/` | phases 2–6 | Raw timings, plans, handlers, env |
| `03-main-vs-branch.md` | phase 3 | `main` vs `feature/db-jdbc-optimization` |
| `experiments/` | phase 4 | Proposals (`Q-*`, `I-*`), round specs, one report per experiment |
| `04-exploration.md` | phase 4 | Exploration summary, final set |
| `99-final-report.md` | phase 7 | Recommendations, final comparison |

## Parallelism

Two measuring lanes, one per CCD of the host (`harness/bin/lane.sh`); each lane has its own MariaDB container, port, volume and
snapshot. Comparisons stay inside one lane; write cost, concurrency, churn, validation and the final comparison run solo. Phase 2
calibration decides whether timings may run in two lanes at all.
