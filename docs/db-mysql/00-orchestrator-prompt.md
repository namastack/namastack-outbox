# Orchestrator prompt — MySQL optimization of `outbox_record`

You are the **orchestrator** of a multi-agent study of SQL query and index performance of the `outbox_record` table on **MySQL**
in Namastack Outbox (read `CLAUDE.md`). You plan, delegate, review and decide. Sub-agents explore, build and measure.

**Goal.** Find the best MySQL indexes and queries for the Priority 1 and 2 workload of ADR-0005, starting from the potential end
solution on `feature/db-jdbc-optimization`, and finish with (1) a measured comparison of `main` vs the branch vs the final solution
and (2) the final MySQL DDL and SQL ready to apply to `feature/db-jdbc-optimization`.

## 0. Ground rules

1. **Models.** You run on Opus 5.5. Spawn every sub-agent with the Agent tool and `model: "sonnet"` (Sonnet 5.5). Give each one:
   the path of its brief in `docs/db-mysql/briefs/`, the concrete parameters (variants, worktrees, lane, labels, output paths),
   and the isolation rule from `briefs/00-isolation-rule.md` **verbatim**.
2. **Isolation.** The rule in `briefs/00-isolation-rule.md` applies to you too: never open, list, grep or summarize `docs/db/`,
   `docs/db-postgresql/` or `docs/db-mariadb/` (in any tree, branch or commit). Their numbers and conclusions must not influence this study.
   The only PostgreSQL knowledge allowed is `docs/db-mysql/reference/postgres-rationale.md` — you may read it, but give it only to the
   sub-agents whose brief allows it (the PostgreSQL-transfer proposer, and experiments you mark as transfer experiments). Treat it as
   hypotheses, not as facts about MySQL.
3. **Priority 3 is never run** (user decision): no timing, plan, equivalence or repository-level call of a RET or ADMIN query, not in
   any phase, not for `main`, not for the branch, not for the final solution. Every `bench`, `equiv`, `repo-bench` and driver call
   uses groups `HOT,WRITE,MON`. MySQL P3 statements are not changed by this study. The project's own Gradle test suites (phase 5) are
   regression tests, not study measurements, and run unchanged.
4. **Decisions inherited from the PostgreSQL study and ADR-0005** (read the ADR in the branch worktree,
   `docs/adr/0005-query-priorities-and-jdbc-dialect-optimization.md`). They are fixed; do not re-litigate them:
   - **Priorities.** P1 scheduling/execution: `keys_strict`, `keys_nonstrict`, `pages`, `insert`, `upd_complete`, `upd_retry`,
     `upd_fail`, `delete_by_id`, and S10 throughput. P2 metrics collector: `count_statuses` (NEW, FAILED), `count_partitions`,
     `oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`. P3 retention/admin: correct SQL and a SQL row limit where
     bounded, slow is acceptable (not run here, see 0.3). The deprecated metrics module (`count_partition_single`,
     `count_new`/`count_failed`/`count_completed` via `countByStatus`) and `count_completed` are not targets.
   - **Index rule.** An index is justified only by P1/P2, and only if its read benefit outweighs its write cost (insert/update
     latency, redo volume, pages written, index size). An index serving only P3 is dropped. Removing indexes to speed up processing
     is an explicit goal. Every DDL change reports write cost.
   - **Partial ownership is the default deployment.** Rank `S8-*` and `S16-*` results first; full ownership is secondary.
   - **`completed_at IS NULL`** holds exactly for NEW and FAILED; P1/P2 queries for NEW/FAILED may add it; queries that may count
     `COMPLETED` must not. `COMPLETED` is not counted by default.
   - **JDBC per dialect, JPA portable.** MySQL gets its own statements in `MySqlJdbcOutboxRecordQueries` (a dialect may change the
     plan and the row-limit syntax, not the result). JPA, MariaDB, other dialects and MongoDB are not changed and JPA is not measured.
   - **Semantics unchanged.** Repository contract (KDoc on `OutboxRecordRepository`), strict per-key ordering, status/retry
     semantics, partition ownership. Every candidate passes the equivalence check (P1/P2 queries).
   - **Non-goals:** column types, the record id format (UUIDv7 is a separate decision, out of scope), making P3 fast.
   - **Rejected:** capping the strict key candidates before the anti-join (`K3`): it can return fewer keys than `:limit`.
   - **Preference:** `ORDER BY` + row limit over `MIN`/`GROUP BY` when performance is equal (aggregates stay for counts). The user
     reports a preliminary PostgreSQL result that `MIN(created_at)` beats `ORDER BY created_at LIMIT 1` for `oldest_created`; the
     branch now uses `MIN` there. Measure both forms on MySQL (mandatory experiment M2).
   - **Timings** cover the HOT, WRITE and MON groups (`--groups HOT,WRITE,MON`).
   - **Per-query limit:** `my-main` 15 min; other variants max(2 × `my-main` median of the same query and label, 60 s), 15 min when
     `my-main` has no median; equivalence 15 min. A stopped query is recorded as "over <limit>".
   - **Staged runs:** screen → confirm → validate. Only `validate` (two passes, reversed order, restart) gives a final win.
   - **Evidence.** Every claim cites a result file. Differences below the noise threshold are "no difference".
   - **Needs a user decision before adoption** (may be tested): index or optimizer hints that name an index (they fail or change
     behaviour if a user's schema lacks it, e.g. before the upgrade steps), generated/virtual columns, functional indexes or any schema
     change beyond plain indexes, histograms (`ANALYZE TABLE ... UPDATE HISTOGRAM` is an operational step users must repeat),
     non-default server settings (including `SET_VAR` hints that change them).
5. **MySQL-specific constraints.**
   - **Dialect detection reaches MariaDB.** A MariaDB server reached through MySQL Connector/J (or with `useMysqlMetadata=true`) is
     detected as MySQL and gets `MySqlJdbcOutboxRecordQueries` and `schema/mysql` (phase 1 confirms this). Prefer forms and DDL that
     also parse and return the same rows on MariaDB; a MySQL-only syntax (`VALUES ROW`, `LATERAL`, some hints) in the final set is
     allowed only with a stated reason and is listed for the user. Phase 5 runs a MariaDB compatibility smoke (correctness only).
   - **Driver.** Applications use MySQL Connector/J; the harness uses it with driver defaults. Phase 1/2 establish what `setMaxRows`
     sends (expected `SET SQL_SELECT_LIMIT` round trips); that cost is part of what is measured (mandatory experiment M9).
   - **Server version.** Primary image `mysql:8.4` (LTS line; pin tag, image id and server version in `STATE.md`). Phase 6 adds a
     sensitivity run on the newest `mysql:9.x` image available (the project's integration tests use `mysql:9.2`).
6. **Workspaces** (siblings of the repository, i.e. `/home/zamo/git/`; never switch the branch of the primary tree, never commit to or
   push existing branches):
   - `../outbox-wt-my-main` → detached at current `main` (pin the sha in `STATE.md`; `f4446c8c` on 2026-11-06).
   - `../outbox-wt-my-opt` → detached at `origin/feature/db-jdbc-optimization` (`2a2663de` on 2026-11-06; try `git fetch` first — it
     may fail for lack of credentials, then use the local `origin/...` ref — and pin the sha you use). Variant names `my-main`, `my-opt`.
   - `../outbox-wt-my-exp-<id>` → local branches `exp/my-<id>`, only when overlays cannot express a change.
   - `../outbox-wt-my-final` → local branch `exp/my-final` (phase 5).
   All reports and results go to `docs/db-mysql/` **in the primary tree**.
7. **Context hygiene.** Sub-agents write details to files and return ≤300 words. Read files selectively. After every step update
   `docs/db-mysql/STATE.md` (phase, done, running, decisions, next step) so the run can be resumed in a new context.
8. **Parallelism.** The host is bare metal with an Intel i7-8700K (6 cores / 12 threads, one shared L3), 15 GiB RAM and ~22 GB free
   disk. There is **one measuring lane** (`harness/bin/lane.sh 1`: DB on cores 1–2, client on core 3; cores 0, 4, 5 free).
   - At most **3 sub-agents at a time**, at most **1 of them measuring** (anything that starts a container step: gen, prepare, bench,
     writecost, concurrency, churn, equiv). Non-measuring sub-agents (proposers, analysis, docs, code without Gradle) may run
     alongside it.
   - **No Gradle build, test run or other heavy CPU/disk work while a timed step runs** (phase 2 calibration measures how much it
     matters). Schedule worktree builds and phase 5 tests between timed runs.
   - A comparison (base vs candidate) always runs back to back on the same snapshot.
   - Before each step the driver records `/proc/loadavg`; a timed step started at load > 3 is rerun.
   - Long runs go to the background; wait for completion notifications rather than polling.
   - Disk: keep ≥ 8 GB free; the harness purges MySQL binary logs after loads (image default `log_bin=ON` is kept).
9. **Server configuration.** `mysql:8.4` image defaults (`innodb_buffer_pool_size` 128 MiB, binary log on), `--cpus=4 --memory=4g`,
   as in the other studies. Record every setting in `env.txt`. Phase 6 adds a 2 GiB buffer-pool run and a `mysql:9.x` run.
10. **User decisions.** You may not be able to ask the user directly. When a decision of the "needs a user decision" kind or any
    other blocking question comes up, write it under "Waiting for user" in `STATE.md` with the options and your recommendation,
    continue with work that does not depend on it, and stop with a short report when nothing else can proceed.

## Phase 0 — Preflight

1. Check: JDK 25 (`java -version`; the harness uses toolchain 25; every Gradle command in a worktree runs with
   `--init-script <primary tree>/docs/db-mysql/harness/gradle/jdk25.init.gradle.kts`, which `bin/variant-build.sh` already does, and
   every sub-agent prompt that runs Gradle in a worktree must say so). Docker, `python3`, `taskset`, image `mysql:8.4` (pull if
   missing; record tag, id, `mysqld --version`), free disk, `/sys/fs/cgroup/cpuset.cpus.effective` and `lscpu -e` against
   `harness/bin/lane.sh`, `/proc/loadavg` at idle.
2. Create the two worktrees, pin their shas in `STATE.md`, build the harness (`cd docs/db-mysql/harness && ./gradlew --console=plain
   -q :sql:installDist`), run `bin/extract-catalog.sh my-main /home/zamo/git/outbox-wt-my-main` then the same for `my-opt` (the first call also builds
   `repo/`, which compiles against `build/variants/my-main.jars`), and `bin/repo-bench <v> hashcheck` for both.
3. Record the MySQL server version and the environment in `STATE.md`.

## Phase 1 — Usage map

One sub-agent with `briefs/01-usage-map.md` → `docs/db-mysql/01-usage-map.md` (main and branch, MySQL path, branch change list).
Review it. Copy the **branch change list** (`B-1`, `B-2`, …) into `STATE.md`; the P1/P2 items drive the ablation (M1); P3-only items
are listed but not measured. Check its dialect-detection section (MariaDB through Connector/J, see 0.5) and its `setMaxRows` section.

## Phase 2 — Harness and calibration

1. One sub-agent with `briefs/02-harness.md` (MySQL plans, handlers, InnoDB write cost, binary-log purge, P3 exclusion in every
   command, S9 with a harness-internal retention delete, S10, repo-level JDBC on MySQL, validation on S1). Review the harness yourself
   before any measurement: warmup, repetitions, identical data across variants, the `setMaxRows` behaviour of Connector/J for `my-main`
   (general_log proof) and `LIMIT` binding for `my-opt`, plan capture under the same `sql_select_limit`, counters taken outside the
   timed section, repo-level `sql.txt` showing `MySqlJdbcOutboxRecordQueries`, no RET/ADMIN id anywhere in the validation results.
2. Then the calibration part of the same brief (same or new sub-agent). Write the **noise thresholds** per group and the result of the
   background-sensitivity check into `STATE.md`.

## Phase 3 — `main` vs branch (first)

Measure the potential end solution before exploring. Sub-agent(s) with `briefs/03-measure.md`, run name `cmp`, variants `my-main`
and `my-opt`, every label of `02-scenarios.md` except `S2L`, two passes in reversed order with a container restart between, plus
equivalence (P1/P2) on `S1`, `S2`, `S5`, `S7`. Lane 1 only.

Write `docs/db-mysql/03-main-vs-branch.md` yourself: per P1/P2 query and label, wins, regressions, plans of every regression and of
every query > 5 ms (index used, rows examined vs returned, filesort, join strategy), write cost, index sizes, S9, S10, equivalence.
Name the **problem cells** that exploration must fix and the branch items that look useless (candidates for the ablation).

## Phase 4 — Exploration

Iterative. Each round has a spec `docs/db-mysql/experiments/round<N>-spec.md` written by you, ids `M..` (mandatory) or `E..`, and a
report per experiment (`briefs/06-experiment.md`). Update the ledger in `STATE.md` after every report.

**4a. Query forms.** Following the method that worked best in the PostgreSQL study — generate queries, then ask for indexes in a new
context, then check variants:
- 1 **independent** query proposer (`briefs/04-propose-queries.md`, no PostgreSQL knowledge), all P1/P2 query ids.
- 1 **PostgreSQL-transfer** proposer (same brief, transfer mode, reads `reference/postgres-rationale.md`).
- They do not measure, so they may start as soon as phase 1 is reviewed and the catalogs exist (in parallel with phase 2/3).
  Deduplicate the forms into `experiments/Q-merged/` (keep ids traceable to the proposer; classify each form: ok, hint, code change,
  status-conditional, MySQL-only syntax).

**4b. Index sets.** 1–2 index proposers in fresh contexts (`briefs/05-propose-indexes.md`), given only the merged SQL files. Merge the
sets with the phase 3 problem cells and the branch ablation into the round 1 screen list. Keep the list runnable: about 8–12 variants
per screen round.

**4c. Screen → confirm → validate.** Screen (one pass per variant, each with its `my-opt` reference on the same snapshot), confirm
the survivors, validate the top 2–3.

**Mandatory experiments** (on `my-opt` as base unless stated):
- **M1 — branch ablation, leave-one-out:** each P1/P2 `B-n` item reverted alone. Which branch changes matter on MySQL?
- **M2 — `oldest_created`:** `MIN(created_at)` vs `ORDER BY created_at LIMIT 1`, both with `completed_at IS NULL`.
- **M3 — `oldest_due_strict` / `oldest_due_nonstrict`:** `MIN` vs `ORDER BY ... LIMIT 1`.
- **M4 — implicit PK suffix:** InnoDB secondary entries already end with `id`; does an explicit `id` key part (as in the branch's
  `(..., created_at, id, ...)` indexes) change plans, ordering use or size?
- **M5 — `pages` forms:** `IN (...)` on the record-key index vs a derived key table (`JSON_TABLE`, `VALUES ROW`) vs `LATERAL` vs
  per-key `UNION ALL`, at batch 10 and `S4-b100`, and under S10.
- **M6 — key selection forms:** the branch's `NOT EXISTS` (check whether MySQL executes it as an antijoin, and with which strategy)
  vs `LEFT JOIN ... IS NULL` vs other forms from 4a, strict and non-strict, especially on `S8-S5` (hot keys) and `S8-S7` (many FAILED).
- **M7 — minimal index set:** the fewest indexes that keep every P1 query and S10 within the noise threshold of the best set, and its
  P2 cost (the write-cost side of the index rule).
- **M8 — `eq_range_index_dive_limit`:** full ownership has 256 partitions in the `IN` list (above the default limit of 200), partial
  ownership 64 or 16. Do plans or estimates change across that boundary? (Observation only; server settings are not changed for
  adoption.)
- **M9 — row limit transport:** cost of `setMaxRows` on Connector/J (extra `SET SQL_SELECT_LIMIT` statements per execution, if phase
  2 confirms them) vs `LIMIT` in SQL alone, for every P1/P2 query that the branch limits; and whether `sql_select_limit` changes
  plans. Result decides whether the MySQL class should bind `LIMIT` and skip `setMaxRows` (a code item for phase 5, results must
  stay identical).

Further ideas (E-series) come from 4a/4b, phase 3 plans and your own reading of plans, for example: covering vs non-covering indexes,
`completed_at` position in the key, `status` vs `completed_at` leading, descending key parts, index skip scan, index merge, hash join
vs nested loop, antijoin/semijoin strategies (`FirstMatch`, `Materialize`, `LooseScan`, `DuplicateWeedout`), `prefer_ordering_index`
behaviour for `ORDER BY ... LIMIT`, invisible indexes as a quick read-only ablation aid (never as evidence for write cost), index hints
(needs user decision), functional indexes / generated columns emulating partial indexes (needs user decision), histograms
(observation; adoption needs user decision).

**Stop** when two consecutive rounds give no improvement above the noise threshold on P1 at partial ownership, or every P1/P2 query
has a justified recommendation. Then write `docs/db-mysql/04-exploration.md` (what won, what was rejected, evidence) and choose the
**final set**. Anything in the "needs a user decision" list goes to "Waiting for user" in `STATE.md` before adoption (0.10).

## Phase 5 — Final candidate in code

One sub-agent with `briefs/07-finalize.md`: worktree `../outbox-wt-my-final` (`exp/my-final` from the pinned branch sha), MySQL DDL
and `MySqlJdbcOutboxRecordQueries`, docs (`upgrading-to-1-12.md`, `database.md`), tests (`:namastack-outbox-jdbc:test`, MySQL
integration tests, `ktlintCheck`) run while no timed step runs, catalog check (`my-final` SQL equals the measured overlays), MariaDB
compatibility smoke. Commit messages on `exp/my-final` use the prefix the branch uses (look at `git log` of the branch; ask via
`STATE.md` if there is no GitHub issue number). Review the diff yourself.

## Phase 6 — Final comparison: `main` vs branch vs final

Lane 1, nothing else running, variants `my-main`, `my-opt`, `my-final` (from its worktree, catalog extracted from code): every label of
`02-scenarios.md` except `S2L`, two passes with reversed variant order and a container restart between, equivalence (P1/P2)
`my-final` vs `my-main` and vs `my-opt` on `S1`, `S2`, `S5`, `S7`. Plus two **sensitivity runs**, one pass each of `my-main` and
`my-final` on `S8-S2`, `S8-S4`, `S8-S5`, `S2-wc` and `S10-S2-p4`:
- buffer pool: `HARNESS_EXTRA_ARGS="--innodb-buffer-pool-size=2G"` (production-like buffer pool);
- server version: `HARNESS_MYSQL_IMAGE=<newest mysql:9.x>` (do conclusions hold on the 9.x line?).
No Priority 3 query is run in any of these.

## Phase 7 — Final report

Write `docs/db-mysql/99-final-report.md` yourself:
1. Executive summary: final vs branch vs `main` on P1 (partial ownership first, S10 throughput), P2, write cost and size.
2. Per P1/P2 query: recommended MySQL SQL and the index it uses, with evidence links; the rejected forms.
3. Keep / drop / modify table for each branch item `B-n` (M1) and each new item.
4. Final MySQL DDL, write cost and storage trade-offs, buffer-pool and 9.x sensitivity results.
5. Risks, semantics checks (equivalence), MariaDB-through-Connector/J compatibility (0.5), Priority 3 not measured (statements
   unchanged), unresolved questions, items waiting for a user decision.
6. The `exp/my-final` branch, its diff summary and test results, and the exact changes to apply to `feature/db-jdbc-optimization`.

Then report in a few lines with the report path, and **ask** (in your final message and in `STATE.md`) whether to apply the final
changes to `feature/db-jdbc-optimization` (as uncommitted changes in a worktree of that branch; the user commits and pushes). Do
nothing outward-facing without the user.
