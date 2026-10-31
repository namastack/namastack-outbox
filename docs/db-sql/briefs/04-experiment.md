# Brief 04 — One experiment

You are a sub-agent. The orchestrator gives you: experiment id `Exx`, hypothesis, base variant
(baseline, v1, or a previous experiment branch), the change to make, scenarios, and comparison
targets (normally baseline and v1). Exploration database: **PostgreSQL**.

## Isolation rule

Never open, list, grep, glob or summarize anything under `docs/db/` (this includes `docs/db/**` in any
worktree). Exclude it from every search. Do not use commit messages, PR texts or docs as the rationale
for v1 changes; judge v1 only by its code and by measurements. If you accidentally read something from
`docs/db/`, stop and report it.

## Task

1. **Change.**
   - Index-only change: write a DDL override file in `docs/db-sql/experiments/Exx-<slug>/` and run the
     harness with it. No code change needed.
   - Query change: create worktree `../outbox-wt-exp-<id>` on a new local branch `exp/<id>` from the
     base, change the JDBC (and JPA if relevant) query code, and update the harness query catalog
     override for this experiment. Keep the repository contract (KDoc on `OutboxRecordRepository`),
     strict per-record-key ordering, status/retry semantics and partition ownership.
2. **Correctness.** Run the equivalence check against the base. For code changes run
   `./gradlew :namastack-outbox-jdbc:test` (and `:namastack-outbox-jpa:test` if JPA changed) in the
   experiment worktree. A failing experiment is reported as such, not fixed by weakening tests.
3. **Measure** on PostgreSQL, the requested scenarios, SQL-level and repository-level as instructed,
   including write cost and table/index sizes. Do not run in parallel with other benchmarks.
4. **Report** `docs/db-sql/experiments/Exx-<slug>.md`:
   - Hypothesis, base, exact change (DDL/diff), branch name if any.
   - Results table: query × scenario, base vs experiment vs other targets, relative change, noise
     threshold from `STATE.md`.
   - Plans before/after for the affected queries (excerpts; full plans in the results dir).
   - Write cost and storage delta.
   - Verdict: `win` / `no difference` / `loss` / `mixed (explain)`, plus dialect portability notes
     (can MySQL, MariaDB, SQL Server, Oracle, H2 express it?).
   - Follow-up ideas.

Return a summary of ≤300 words: verdict, key numbers, report path.
