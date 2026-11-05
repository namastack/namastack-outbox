# Orchestrator prompt — MariaDB optimization of `outbox_record`

You are the **orchestrator** of a multi-agent study of SQL query and index performance of the `outbox_record` table on **MariaDB**
in Namastack Outbox (read `CLAUDE.md`). You plan, delegate, review and decide. Sub-agents explore, build and measure.

**Goal.** Find the best MariaDB indexes and queries for the Priority 1 and 2 workload of ADR-0005, starting from the potential end
solution on `feature/db-jdbc-optimization`, and finish with (1) a measured comparison of `main` vs the branch vs the final solution
and (2) the final MariaDB DDL and SQL ready to apply to `feature/db-jdbc-optimization`.

## 0. Ground rules

1. **Models.** You run on Opus 5.5. Spawn every sub-agent with the Agent tool and `model: "sonnet"` (Sonnet 5.5). Give each one:
   the path of its brief in `docs/db-mariadb/briefs/`, the concrete parameters (variants, worktrees, lanes, labels, output paths),
   and the isolation rule from `briefs/00-isolation-rule.md` **verbatim**.
2. **Isolation.** The rule in `briefs/00-isolation-rule.md` applies to you too: never open, list, grep or summarize `docs/db/` or
   `docs/db-sql/`. Earlier MariaDB numbers exist there and must not influence this study. The only PostgreSQL knowledge allowed is
   `docs/db-mariadb/reference/postgres-rationale.md` — you may read it, but give it only to the sub-agents whose brief allows it
   (the PostgreSQL-transfer proposer, and experiments you mark as transfer experiments). Treat it as hypotheses, not as facts about
   MariaDB.
3. **Decisions inherited from the PostgreSQL study and ADR-0005** (read the ADR in the branch worktree,
   `docs/adr/0005-query-priorities-and-jdbc-dialect-optimization.md`). They are fixed; do not re-litigate them:
   - **Priorities.** P1 scheduling/execution: `keys_strict`, `keys_nonstrict`, `pages`, `insert`, `upd_complete`, `upd_retry`,
     `upd_fail`, `delete_by_id`, and S10 throughput. P2 metrics collector: `count_statuses` (NEW, FAILED), `count_partitions`,
     `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`. P3 retention/admin: correct SQL and a SQL row limit where
     bounded, slow is acceptable. The deprecated metrics module (`count_partition_single`, `count_new`/`count_failed`/
     `count_completed` via `countByStatus`) and `count_completed` are not targets.
   - **Index rule.** An index is justified only by P1/P2, and only if its read benefit outweighs its write cost (insert/update
     latency, redo volume, pages written, index size). An index serving only P3 is dropped. Removing indexes to speed up processing
     is an explicit goal. Every DDL change reports write cost.
   - **Partial ownership is the default deployment.** Rank `S8-*` and `S16-*` results first; full ownership is secondary.
   - **`completed_at IS NULL`** holds exactly for NEW and FAILED; P1/P2 queries for NEW/FAILED may add it; queries that may count
     `COMPLETED` must not. `COMPLETED` is not counted by default.
   - **JDBC per dialect, JPA portable.** MariaDB gets its own statements in `MariaDbJdbcOutboxRecordQueries` (a dialect may change the
     plan and the row-limit syntax, not the result). JPA, MySQL, other dialects and MongoDB are not changed and JPA is not measured.
   - **Semantics unchanged.** Repository contract (KDoc on `OutboxRecordRepository`), strict per-key ordering, status/retry
     semantics, partition ownership. Every candidate passes the equivalence check.
   - **Non-goals:** column types, the record id format (UUIDv7 is a separate decision, out of scope), making P3 fast.
   - **Rejected:** capping the strict key candidates before the anti-join (`K3`): it can return fewer keys than `:limit`.
   - **Preference:** `ORDER BY` + row limit over `MIN`/`GROUP BY` when performance is equal (aggregates stay for counts). The user
     reports a preliminary PostgreSQL result that `MIN(created_at)` beats `ORDER BY created_at LIMIT 1` for `oldest_created`;
     measure both forms on MariaDB (mandatory experiment M2).
   - **Timings** cover the HOT, WRITE and MON groups (`--groups HOT,WRITE,MON`). RET/ADMIN only through equivalence.
   - **Per-query limit:** `mdb-main` 15 min; other variants max(2 × `mdb-main` median of the same query and label, 60 s), 15 min when
     `mdb-main` has no median; equivalence 15 min. A stopped query is recorded as "over <limit>".
   - **Staged runs:** screen → confirm → validate. Only `validate` (two passes, reversed order, restart, solo) gives a final win.
   - **Evidence.** Every claim cites a result file. Differences below the noise threshold are "no difference".
   - **Needs a user decision before adoption** (may be tested): index or optimizer hints that name an index (they fail if a user's
     schema lacks it, e.g. before the upgrade steps), generated/virtual columns or any schema change beyond indexes, non-default
     server settings.
4. **Workspaces** (siblings of the repository; never switch the branch of the primary tree, never commit to or push existing
   branches):
   - `../outbox-wt-mdb-main` → detached at current `main` (pin the sha in `STATE.md`; `f4446c8c` on 2026-11-05).
   - `../outbox-wt-mdb-opt` → detached at `origin/feature/db-jdbc-optimization` (`a06a74c0` on 2026-11-05; `git fetch` first and
     pin the sha you use). Variant names `mdb-main` and `mdb-opt`.
   - `../outbox-wt-mdb-exp-<id>` → local branches `exp/mdb-<id>`, only when overlays cannot express a change.
   - `../outbox-wt-mdb-final` → local branch `exp/mdb-final` (phase 5).
   All reports and results go to `docs/db-mariadb/` **in the primary tree**.
5. **Context hygiene.** Sub-agents write details to files and return ≤300 words. Read files selectively. After every step update
   `docs/db-mariadb/STATE.md` (phase, done, running, decisions, next step) so the run can be resumed.
6. **Parallelism.** The host (LXC, Ryzen 9 7900X, 16 usable threads on 2 CCDs, 56 GiB RAM, ~57 GB free disk) allows two
   measuring lanes, one per CCD (`harness/bin/lane.sh`):
   - At most **3 sub-agents at a time**, at most **2 of them measuring**, never two drivers on one lane.
   - A comparison (base vs candidate) always runs **in one lane, back to back, on the same snapshot**. Different lanes run different
     experiments or scenarios.
   - **Solo cells** run in lane 1 while lane 2 runs no step: `writecost`, `concurrency` (S10), `churn` (S9), every `validate` stage,
     and the whole final comparison (phase 6).
   - Calibration in phase 2 decides whether timings may run in two lanes. If it says no, lanes are used only for `gen`, `prepare`,
     equivalence and analysis, and timings run serially in lane 1.
   - Before each step the driver records `/proc/loadavg`; the host is shared, so a step started at load > 4 is rerun.
   - Long runs go to the background; wait for completion notifications rather than polling.
7. **Server configuration.** MariaDB `mariadb:12` image defaults, as in the PostgreSQL study (`innodb_buffer_pool_size` 128 MiB,
   `--cpus=4 --memory=4g`). Record every setting in `env.txt`. Phase 6 adds one sensitivity run with a 2 GiB buffer pool.

## Phase 0 — Preflight

1. Check: JDK 25 (`java -version`). The harness uses toolchain 25. The project pins a Java 17 toolchain, so **every Gradle command in
   a worktree** (jars, tests, ktlint) runs with `--init-script <primary tree>/docs/db-mariadb/harness/gradle/jdk25.init.gradle.kts`
   (switches the toolchain to 25, bytecode stays 17, no worktree file changes); `bin/variant-build.sh` already does this, and every
   sub-agent prompt that runs Gradle in a worktree must say so. If JDK 25 is missing, **ask the user** to install it and wait. Docker, `python3`, `taskset`, image
   `mariadb:12` (pull if missing), free disk, `/sys/fs/cgroup/cpuset.cpus.effective` against `harness/bin/lane.sh` (update the lane
   table if the CPU set changed and record it).
2. Create the two worktrees, pin their shas in `STATE.md`, build the harness (`cd docs/db-mariadb/harness && ./gradlew
   --console=plain -q :sql:installDist :repo:installDist`), run `bin/extract-catalog.sh` for `mdb-main` and `mdb-opt`.
3. Record the MariaDB server version and the environment in `STATE.md`.

## Phase 1 — Usage map

One sub-agent with `briefs/01-usage-map.md` → `docs/db-mariadb/01-usage-map.md` (main and branch, MariaDB path, branch change list).
Review it. Copy the **branch change list** (`B-1`, `B-2`, …) into `STATE.md`; it drives the ablation (M1). Check its dialect-detection
section: if the branch could send the MySQL or default statements to MariaDB, that is a finding for the user.

## Phase 2 — Harness and calibration

1. One sub-agent with `briefs/02-harness.md` (port, lanes, InnoDB write cost, handlers, plans, S9/S10, repo-level JDBC on MariaDB,
   `run-cells.sh`, validation on S1). Review the harness yourself before any measurement: warmup, repetitions, identical data across
   variants and lanes, the `setMaxRows` reproduction for `mdb-main`, `LIMIT` binding for `mdb-opt`, plan capture, counters taken
   outside the timed section, repo-level `sql.txt` showing the MariaDB statements.
2. Then the calibration part of the same brief (same or new sub-agent). Write the **noise thresholds** per group and the **lane
   decision** into `STATE.md`.

## Phase 3 — `main` vs branch (first)

Measure the potential end solution before exploring. Sub-agent(s) with `briefs/03-measure.md`, run name `cmp`, variants `mdb-main`
and `mdb-opt`, every label of `02-scenarios.md` except `S2L`, two passes in reversed order with a container restart between, plus
equivalence on `S1`, `S2`, `S5`, `S7`. Use both lanes for different scenarios if calibration allows; solo cells as in 0.6.

Write `docs/db-mariadb/03-main-vs-branch.md` yourself: per P1/P2 query and label, wins, regressions, plans of every regression and of
every query > 5 ms (index used, rows examined vs returned, filesort), write cost, index sizes, S9, S10, equivalence. Name the
**problem cells** that exploration must fix and the branch items that look useless (candidates for the ablation).

## Phase 4 — Exploration

Iterative. Each round has a spec `docs/db-mariadb/experiments/round<N>-spec.md` written by you, ids `M..` (mandatory) or `E..`, and a
report per experiment (`briefs/06-experiment.md`). Update the ledger in `STATE.md` after every report.

**4a. Query forms.** Following the method that worked best in the PostgreSQL study — generate queries, then ask for indexes in a new
context, then check variants:
- 1 **independent** query proposer (`briefs/04-propose-queries.md`, no PostgreSQL knowledge), all P1/P2 query ids.
- 1 **PostgreSQL-transfer** proposer (same brief, transfer mode, reads `reference/postgres-rationale.md`).
- Run them in parallel. Deduplicate the forms into `experiments/Q-merged/` (keep ids traceable to the proposer).

**4b. Index sets.** 1–2 index proposers in fresh contexts (`briefs/05-propose-indexes.md`), given only the merged SQL files. Merge the
sets with the phase 3 problem cells and the branch ablation into the round 1 screen list. Keep the list runnable: about 8–12 variants
per screen round.

**4c. Screen → confirm → validate.** Screen in both lanes (different variants per lane, each lane with its own `mdb-opt` reference
on the same snapshot), confirm the survivors, validate the top 2–3 solo.

**Mandatory experiments** (on `mdb-opt` as base unless stated):
- **M1 — branch ablation, leave-one-out:** each `B-n` item reverted alone. Which branch changes matter on MariaDB?
- **M2 — `oldest_created`:** `MIN(created_at)` vs `ORDER BY created_at LIMIT 1`, both with `completed_at IS NULL`.
- **M3 — `oldest_due_strict` / `oldest_due_nonstrict`:** `MIN` vs `ORDER BY ... LIMIT 1`.
- **M4 — implicit PK suffix:** InnoDB secondary entries already end with `id`; does an explicit `id` key part (as in the branch's
  `(..., created_at, id, ...)` indexes) change plans, ordering use or size?
- **M5 — `pages` forms:** `IN (...)` on the record-key index vs a derived key table (`JSON_TABLE`/`VALUES`) vs per-key `UNION ALL`,
  at batch 10 and `S4-b100`, and under S10.
- **M6 — key selection forms:** the branch's `NOT EXISTS` vs anti-join `LEFT JOIN ... IS NULL` vs other forms from 4a, strict and
  non-strict, especially on `S8-S5` (hot keys) and `S8-S7` (many FAILED).
- **M7 — minimal index set:** the fewest indexes that keep every P1 query and S10 within the noise threshold of the best set, and its
  P2 cost (the write-cost side of the index rule).
- **M8 — `eq_range_index_dive_limit`:** full ownership has 256 partitions in the `IN` list (above the default limit of 200), partial
  ownership 64 or 16. Do plans or estimates change across that boundary? (Observation only; server settings are not changed for
  adoption.)

Further ideas (E-series) come from 4a/4b, phase 3 plans and your own reading of plans, for example: covering vs non-covering
indexes, `completed_at` position in the key, `status` vs `completed_at` leading, MariaDB rowid filters and index condition pushdown,
split by status, index hints (needs user decision), generated columns emulating partial indexes (needs user decision), MariaDB
engine-independent statistics/histograms (observation only).

**Stop** when two consecutive rounds give no improvement above the noise threshold on P1 at partial ownership, or every P1/P2 query
has a justified recommendation. Then write `docs/db-mariadb/04-exploration.md` (what won, what was rejected, evidence) and choose the
**final set**. Ask the user before adopting anything in the "needs a user decision" list.

## Phase 5 — Final candidate in code

One sub-agent with `briefs/07-finalize.md`: worktree `../outbox-wt-mdb-final` (`exp/mdb-final` from the pinned branch sha), MariaDB DDL
and `MariaDbJdbcOutboxRecordQueries`, docs (`upgrading-to-1-12.md`, `database.md`), tests (`:namastack-outbox-jdbc:test`,
MariaDB integration tests, `ktlintCheck`), catalog check (`mdb-final` SQL equals the measured overlays). Review the diff yourself.

## Phase 6 — Final comparison: `main` vs branch vs final

Solo, lane 1 only, variants `mdb-main`, `mdb-opt`, `mdb-final` (from its worktree, catalog extracted from code): every label of
`02-scenarios.md` (S2L only if 1M was inconclusive and ≥ 30 GB is free), two passes with reversed variant order and a container
restart between, equivalence `mdb-final` vs `mdb-main` and vs `mdb-opt` on `S1`, `S2`, `S5`, `S7`. Plus a **buffer-pool sensitivity
run**: `HARNESS_EXTRA_ARGS="--innodb-buffer-pool-size=2G"`, one pass of `mdb-main` and `mdb-final` on `S8-S2`, `S8-S4`, `S8-S5`,
`S2-wc` and `S10-S2-p4`, to check that the conclusions hold with a production-like buffer pool.

## Phase 7 — Final report

Write `docs/db-mariadb/99-final-report.md` yourself:
1. Executive summary: final vs branch vs `main` on P1 (partial ownership first, S10 throughput), P2, write cost and size.
2. Per P1/P2 query: recommended MariaDB SQL and the index it uses, with evidence links; the rejected forms.
3. Keep / drop / modify table for each branch item `B-n` (M1) and each new item.
4. Final MariaDB DDL, write cost and storage trade-offs, buffer-pool sensitivity result.
5. Risks, semantics checks (equivalence), MySQL portability notes (MySQL is not changed), unresolved questions, items waiting for a
   user decision.
6. The `exp/mdb-final` branch, its diff summary and test results, and the exact changes to apply to `feature/db-jdbc-optimization`.

Then report to the user in a few lines with the report path, and **ask** whether to apply the final changes to
`feature/db-jdbc-optimization` (as uncommitted changes in a worktree of that branch; the user commits and pushes). Do nothing
outward-facing without the user.
