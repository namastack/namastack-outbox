# SQL optimization round 2 — `outbox_record`

A self-contained, multi-agent prompt for a fresh round of SQL query and index optimization of the
`outbox_record` table, used through `OutboxRecordRepository`.

- **Orchestrator:** Opus 5.5 (the session you start).
- **Sub-agents:** Sonnet 5.5 (spawned by the orchestrator with `model: "sonnet"`).
- **Variants compared:** `main` (baseline), `feature/db-optimalization` @ `e0b09b4` (v1), and new
  experimental variants.
- **Databases:** all JDBC dialects (PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, H2) plus JPA on
  PostgreSQL. MongoDB is out of scope.
- **Exploration database:** PostgreSQL.

## How to run

From the repository root (Docker must be running):

```bash
claude --model opus
```

Then say:

> Execute `docs/db-sql/00-orchestrator-prompt.md`.

To resume an interrupted run, say:

> Resume `docs/db-sql/00-orchestrator-prompt.md` from `docs/db-sql/STATE.md`.

## Isolation rule

The first optimization round has notes, results and findings in `docs/db/**`. **No agent may open,
list, grep or summarize anything under `docs/db/`.** v1 is judged only by its code and by new
measurements. Every brief repeats this rule.

## Layout

| Path | Written by | Purpose |
|---|---|---|
| `00-orchestrator-prompt.md` | — | The orchestrator prompt |
| `briefs/01-explore-usage.md` | — | Sub-agent brief: repository usage map |
| `briefs/02-harness.md` | — | Sub-agent brief: benchmark harness and scenario data |
| `briefs/03-measure.md` | — | Sub-agent brief: run a measurement matrix |
| `briefs/04-experiment.md` | — | Sub-agent brief: one experiment |
| `briefs/05-cross-dialect.md` | — | Sub-agent brief: port and validate winners on all dialects |
| `STATE.md` | orchestrator | Progress ledger, decisions, open questions |
| `01-usage-map.md` | phase 1 | How `outbox_record` is used, grouped by purpose |
| `02-scenarios.md` | phase 2 | Scenario matrix definition |
| `harness/` | phase 2 | Scripts, data generators, query catalogs |
| `results/<variant>/<dialect>/<scenario>/` | phases 3–6 | Raw timings and plans |
| `03-baseline.md`, `04-v1.md` | phases 3–4 | Summaries |
| `experiments/Exx-<slug>.md` | phase 5 | One file per experiment |
| `06-cross-dialect.md` | phase 6 | Cross-dialect validation |
| `99-final-report.md` | phase 7 | Recommendations |
