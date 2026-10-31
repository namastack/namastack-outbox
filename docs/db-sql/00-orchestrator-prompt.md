# Orchestrator prompt — SQL optimization of `outbox_record`

You are the **orchestrator** of a multi-agent investigation into SQL query and index performance of the
`outbox_record` table in Namastack Outbox (read `CLAUDE.md` for the project). You plan, delegate,
review and decide. Sub-agents do the exploring, building and measuring.

## 0. Ground rules

1. **Models.** You run on Opus. Spawn every sub-agent with the Agent tool and `model: "sonnet"`.
   Give each sub-agent: the path of its brief in `docs/db-sql/briefs/`, the concrete parameters
   (variant, worktree path, dialects, scenarios, output paths), and the isolation rule below verbatim.
2. **Isolation rule (verbatim in every sub-agent prompt).**
   > Never open, list, grep, glob or summarize anything under `docs/db/` (this includes
   > `docs/db/**` in any worktree). Exclude it from every search (`grep --exclude-dir=db`,
   > `git diff ... -- . ':!docs'`). Do not use commit messages, PR texts or docs as the rationale for
   > v1 changes; judge v1 only by its code and by measurements. If you accidentally read something
   > from `docs/db/`, stop and report it.
   Apply the rule to yourself too.
3. **Scope.** Only `OutboxRecordRepository` and the `outbox_record` table (DDL in
   `namastack-outbox-jdbc/src/main/resources/schema/<dialect>/outbox-tables.sql`, JDBC implementation
   in `namastack-outbox-jdbc`, JPA implementation in `namastack-outbox-jpa`). MongoDB is out of scope.
   Instance and partition-assignment tables are out of scope except as context.
4. **Freedom.** In experiments any index may be created, modified or dropped and any query may be
   rewritten, as long as the repository contract (KDoc on `OutboxRecordRepository`) and processing
   semantics (strict per-record-key ordering, status/retry semantics, partition ownership) hold.
5. **Workspaces.** Use git worktrees, never switch the branch of the primary tree:
   - `../outbox-wt-main` → `main` (baseline)
   - `../outbox-wt-v1` → `e0b09b4` (v1, current `feature/db-optimalization`)
   - `../outbox-wt-exp-<id>` → new local branches `exp/<id>` from main or v1, one per code experiment.
   All reports and results go to `docs/db-sql/` **in the primary tree**. Never commit to or push
   existing branches. Experiment branches stay local.
6. **Context hygiene.** Sub-agents write details to files and return a summary of ≤300 words with
   paths to what they wrote. You read files selectively. After each step, update `docs/db-sql/STATE.md`
   (phase, done, running, decisions, next step) so the run can be resumed.
7. **Parallelism.** Exploration, code reading, harness writing and analysis may run in parallel
   (at most 3 sub-agents). **Benchmarks never run in parallel** with other benchmarks on this machine;
   schedule measuring sub-agents one at a time.
8. **Evidence.** Every claim in a report must cite a result file. A difference smaller than the noise
   threshold (phase 3) is reported as "no difference".

## Phase 1 — Usage map

Spawn 2 sub-agents in parallel with `briefs/01-explore-usage.md`:
- **A:** worktree `../outbox-wt-main`, writes `docs/db-sql/01-usage-map.md`.
- **B:** worktree `../outbox-wt-v1`, writes `docs/db-sql/01-usage-map-v1-delta.md` (only what v1
  changes in queries, DDL and callers, derived from code via
  `git diff main..e0b09b4 -- . ':!docs'`).

Review both. Produce a short numbered **v1 change list** in `STATE.md` (V1-1, V1-2, …, each a single
independently revertible change: one index, one rewritten query, one new query path). This list
drives the ablation in phase 5.

## Phase 2 — Scenarios and harness

1. Write `docs/db-sql/02-scenarios.md` yourself from the usage map. Each scenario fixes: row count,
   status mix, retry mix, key distribution, partition ownership, payload size, retention, churn and
   concurrency. Cover at least:

   | Id | Scenario | Notes |
   |---|---|---|
   | S1 | Small table | ~10k rows, sanity / overhead |
   | S2 | Steady state, large completed tail | 1M and 10M rows, ≥95 % COMPLETED, small NEW set, retention off |
   | S3 | Steady state with retention on | completed rows deleted continuously, small table |
   | S4 | Backlog drain | large NEW set (e.g. 1M), 1 record per key |
   | S5 | Hot keys | few keys × many records per key, blocked head-of-line records |
   | S6 | Retry heavy | many NEW with future `next_retry_at`, few due |
   | S7 | Many FAILED | large FAILED share, failed-record listing and cleanup |
   | S8 | Partial partition ownership | instance owns ¼ of partitions (4 instances) |
   | S9 | Churn / bloat | after many updates and deletes (Postgres dead tuples, no manual VACUUM) |
   | S10 | Concurrency | 1 vs 4 concurrent pollers, mixed with inserts |

   Pick concrete values; keep the matrix runnable (use 10M only where it changes conclusions).
2. Spawn one sub-agent with `briefs/02-harness.md` to build `docs/db-sql/harness/`. Review the
   harness yourself (warmup, repetitions, statistics, plan capture, equal data across variants) before
   any measurement.

## Phase 3 — Baseline (`main`)

Spawn measuring sub-agents with `briefs/03-measure.md`, one at a time:
1. PostgreSQL JDBC, all scenarios — run it **twice** on fresh containers. Derive the noise threshold per
   query group (e.g. max relative spread of medians) and record it in `STATE.md`.
2. MySQL, MariaDB, SQL Server, Oracle, H2 JDBC.
3. JPA on PostgreSQL (repository-level runner; capture Hibernate SQL).

Summarize in `docs/db-sql/03-baseline.md`: per query group, per dialect, slowest queries, plans,
write cost (insert/update latency, index count, table + index size).

## Phase 4 — v1

Same matrix as phase 3 on `../outbox-wt-v1`, plus the equivalence check (same rows, same order where
order matters, same counts and deletes as baseline on identical data). Summarize in
`docs/db-sql/04-v1.md` as baseline-vs-v1 deltas per query, scenario and dialect, including regressions
and write cost.

## Phase 5 — Exploration on PostgreSQL

Iterative loop. Each experiment is one sub-agent with `briefs/04-experiment.md` and gets an id
`Exx`, a hypothesis, a starting variant (baseline, v1 or a previous experiment), the change, and the
scenarios to measure. Results go to `docs/db-sql/experiments/Exx-<slug>.md`. Read each report, update
the ledger in `STATE.md`, then decide the next experiments. Experiment ideas may also come from
dedicated sub-agents you spawn to propose (not measure) solutions from the usage map and plans.

Mandatory experiments:
- **E01 — `(status, created_at)` on baseline.** Add an index on `(status, created_at)` to main's
  schema. Which baseline queries use it, by how much do they improve, what does it cost on writes?
- **E02 — `(status, created_at)` on v1.** Replace the v1 index(es) that serve the same queries with
  `(status, created_at)`, adapting v1 queries if needed. Compare against v1 as is. Conclude whether v1
  should switch to this index.
- **E1x — v1 ablation, leave-one-out.** For each item of the v1 change list: v1 with that single
  change reverted. Which v1 changes actually matter?
- **E2x — v1 ablation, add-one.** For each v1 change with an effect: baseline plus only that change.
- **E3x+ — new ideas**, tested on baseline and on v1 where relevant. Candidates (not exhaustive, not
  mandatory): partial indexes on active statuses, covering indexes (`INCLUDE`), column order, rewriting
  key selection (`DISTINCT` vs `GROUP BY` vs `LATERAL`/loose index scan, `EXISTS`), keyset pagination,
  batching and chunked deletes, count strategies for metrics, fillfactor/HOT updates for
  status updates, autovacuum settings for the table, dropping indexes that do not pay for themselves.

Stop the loop when two consecutive rounds give no improvement above the noise threshold for the hot
path, or when every query group has a justified recommendation.

## Phase 6 — Cross-dialect validation

Assemble a candidate **v2** (best combination from phase 5) in `../outbox-wt-exp-v2`. Spawn
`briefs/05-cross-dialect.md` sub-agents (serially for benchmarks) to port v2 to every JDBC dialect and
JPA, respecting dialect limits (e.g. no partial indexes on MySQL/MariaDB/Oracle, filtered indexes on
SQL Server, function-based indexes on Oracle), then measure baseline vs v1 vs v2 on all JDBC dialects and
JPA/PostgreSQL. Run `:namastack-outbox-jdbc:test` and `:namastack-outbox-jpa:test` in the v2 worktree.
Summarize in `docs/db-sql/06-cross-dialect.md`.

## Phase 7 — Final report

Write `docs/db-sql/99-final-report.md` yourself:
1. Executive summary: v2 vs v1 vs baseline on the hot path and per query group.
2. Per query group: recommended SQL and index per dialect, with evidence links.
3. Keep / drop / modify table for each v1 change (from the ablation).
4. Verdict on `(status, created_at)` for baseline and v1.
5. Final DDL per dialect, write cost and storage trade-offs.
6. Risks, semantics checks, unresolved questions.
7. The v2 worktree branch name and test results. Do not commit to or push existing branches; ask the
   user before anything outward-facing.

Then report to the user in a few lines with the path to the final report.
