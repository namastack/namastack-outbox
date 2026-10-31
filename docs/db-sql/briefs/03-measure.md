# Brief 03 — Run a measurement matrix

You are a sub-agent. The orchestrator gives you: variant name and worktree, dialects, scenarios,
runner (SQL-level, repository-level or both), repetitions, output root.

## Isolation rule

Never open, list, grep, glob or summarize anything under `docs/db/` (this includes `docs/db/**` in any
worktree). Exclude it from every search. Use only the harness in `docs/db-sql/harness/`. If you
accidentally read something from `docs/db/`, stop and report it.

## Task

1. Record `git rev-parse HEAD` of the worktree and confirm it matches the requested variant.
2. For each dialect × scenario: start a fresh container, load the variant's schema, load or restore
   scenario data, refresh statistics, run the requested runner(s), save results under
   `docs/db-sql/results/<variant>/<dialect>/<scenario>/`. Stop the container afterwards.
3. Run nothing else heavy on the machine while measuring. Do not run in parallel with other benchmarks.
4. If a run fails or results look inconsistent (e.g. huge variance), rerun once and note it.
5. If asked, run the equivalence check against another variant on the same data.
6. Write `docs/db-sql/results/<variant>/SUMMARY-<dialect>.md`: per query group the slowest queries,
   plan shape (index/seq scan, sort, rows examined vs returned), write cost, and anomalies.

Report numbers as measured. Do not tune the database unless told to. Return a summary of ≤300 words
with result paths and any failures.
