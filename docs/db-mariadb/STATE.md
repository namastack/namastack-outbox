# State ledger (MariaDB study)

Maintained by the orchestrator. Update after every step.

## Current phase

Phase 6 (final comparison main / branch / final). Phase 5 done 2026-11-07 (`05-final-candidate.md`, `exp/mdb-final` `f31bc505`). Phase 4 done 2026-11-07 (`04-exploration.md`; final set = `c-1`). Rounds 1a, 1b, 2 done 2026-11-06. Phase 3 done 2026-11-06 (`03-main-vs-branch.md`). Phases 0-2 done 2026-11-05. Phases 0-1 and phase 2 port/validation done 2026-11-05.

## Environment

- Host (2026-11-05): LXC container, AMD Ryzen 9 7900X, cgroup cpuset `0,2-5,7,9-11,13-15,17-18,21,23` (16 threads, 2 CCDs),
  56 GiB RAM, ~57 GB free on `/`, Docker 26.1.4. JDK: OpenJDK 25.0.4.1 only (Ubuntu package); worktree builds use `harness/gradle/jdk25.init.gradle.kts`.
- Lanes (`harness/bin/lane.sh`): lane 1 db `2,14,3,15` client `0,4,13` (CCD0); lane 2 db `9,21,11,23` client `7,10,18` (CCD1);
  `5,17` free for orchestrator/Gradle. Container limits `--cpus=4 --memory=4g`.
- MariaDB image `mariadb:12` = 12.3.3-MariaDB-ubu2404 on 2026-11-05 (smoke test): `innodb_buffer_pool_size` 128 MiB,
  `innodb_flush_method` O_DIRECT, `innodb_flush_log_at_trx_commit` 1, `log_bin` OFF, `eq_range_index_dive_limit` 200,
  `use_stat_tables` PREFERABLY_FOR_QUERIES, `optimizer_use_condition_selectivity` 4, `histogram_size` 254.

## Pinned variants

| Variant | Worktree | Sha | Notes |
|---|---|---|---|
| `mdb-main` | `../outbox-wt-mdb-main` | `f4446c8c0b2c0767f54a168d82bcf0c5fc85ca9f` | detached, = `origin/main` |
| `mdb-opt` | `../outbox-wt-mdb-opt` | `2a2663de2f2028790180399293981f7eca587ad1` | detached, `origin/feature/db-jdbc-optimization` (re-pinned 2026-11-05 on user request; was `a06a74c0`) |

## Noise thresholds

(phase 2 calibration)

| Query group | Threshold | Source |
|---|---|---|
| HOT | 11 % relative (max spread; p90 8 %) | `results/calibration/REPORT.md`, `noise-pass1-pass2.md` |
| MON | 10 % relative (p90 4 %); < 0.3 ms: 0.04 ms absolute | same |
| WRITE (rolled-back single statements) | 60 % relative and >= 0.2 ms absolute (p90 30 %) — timings weak; judge write cost by `writecost` | same |
| writecost redo bytes/write | 12 % (typ. 1-4 %) | `writecost-noise.md` |
| writecost latency p50 / p95 | 8 % / 25 % | same |
| writecost pages/write | 64 % (erratic; supporting evidence only) | same |

Measured with host load 11-13 from outside the container (another tenant); see Decisions.

## Lane decision

Calibration recommendation (b), adopted: only `gen`, `prepare` and equivalence in parallel; every timed cell serial in lane 1 with
lane 2 idle (interference: HOT +9-14 % / MON +70 % under gen+bench, HOT +43 % under S10). Snapshots identical across lanes
(`CHECKSUM TABLE`: S2 3370317100, S4 593475532, S5 3839299731).

## Branch change list

From `01-usage-map.md` §7 (branch `a06a74c0` vs main `f4446c8c`):

- **B-1** DDL: main's 5 secondary indexes replaced by 4; split for ablation:
  - **B-1a** `idx_outbox_record_status_completed_created_id (status, completed_at, created_at, id, partition_no, next_retry_at, record_key)`
  - **B-1b** `idx_outbox_record_key_completed_created_id (record_key, completed_at, created_at, id)`
  - **B-1c** `idx_outbox_record_status_completed_retry (status, completed_at, next_retry_at, partition_no)`
  - **B-1d** `idx_outbox_record_status_completed_partition (status, completed_at, partition_no)`
- **B-2** `keys_strict`: `o.completed_at IS NULL` + predecessor `NOT EXISTS` with `earlier.completed_at IS NULL` and `created_at <=` range.
- **B-3** `keys_nonstrict`: `GROUP BY record_key ORDER BY MIN(created_at)` -> first-due-per-key `NOT EXISTS`, `ORDER BY created_at, id`.
- **B-4** `pages`, `find_incomplete_by_key`: `AND completed_at IS NULL`.
- **B-5** `count_statuses` (grouped, NEW+FAILED default) replaces 3 `countByStatus` calls.
- **B-6** `oldest_created`: adds `completed_at IS NULL`; at `2a2663de` the form is `MIN(created_at)` again (no `ORDER BY`/`LIMIT`, no
  `setMaxRows`). Revert = main SQL. (At `a06a74c0` it was `ORDER BY created_at LIMIT 1`; that form is `Q-merged/oldest_created/oc-06-order-limit`.)
- **B-7** `oldest_due_strict` -> `ORDER BY next_retry_at LIMIT 1` with B-2 sub-select; `oldest_due_nonstrict` adds `completed_at IS NULL` (MIN).
- **B-8** `LIMIT :limit` on retention selects (P3) + `setMaxRows` on keys/oldest.
- **B-9** dialect machinery refactor (no MariaDB SQL change by itself; not an ablation item).

## Experiments ledger

| Id | Hypothesis | Base | Stage | Verdict | Report |
|---|---|---|---|---|---|

## Decisions

- Inherited decisions: section 0.3 of `00-orchestrator-prompt.md`.
- 2026-11-05 (preparation session): JDK 25 only on the host. Harness toolchain 25; worktree builds use
  `harness/gradle/jdk25.init.gradle.kts`. Pre-check done: harness builds, `hashcheck` passes, catalog extraction works, lane-1 MariaDB
  S1 smoke bench ran (results discarded). Phase 2 still validates the full harness.

- 2026-11-05 phase 0: JDK 25.0.4.1, Docker 26.1.4, python 3.13.13, taskset present, image `mariadb:12` id `2215e9af9663`
  (`mariadbd` 12.3.3-MariaDB-ubu2404), 56 GB free, cpuset unchanged (`lane.sh` table valid). Harness built (`:sql`, `:repo`
  installDist; `:repo` needs `build/variants/mdb-main.jars` first). Catalogs extracted: `mdb-main` 26 queries (repository-fields),
  `mdb-opt` 28 (query-package). `hashcheck` 0/20004 mismatches for both.

- Phase 1 (2026-11-05): MariaDB/MySQL/default classes render identical MariaDB SQL on the branch (MariaDB class has no overrides).
  Connector/J 3.5.10 against MariaDB >= 10.3 rewrites `setMaxRows(n)` to `SET STATEMENT SQL_SELECT_LIMIT=n FOR <sql>` (server-side limit);
  harness must use the MariaDB driver and plans for `setMaxRows` queries must be captured under the same `SQL_SELECT_LIMIT`.
- **Finding for the user (dialect detection):** a MariaDB server reached through MySQL Connector/J or with `useMysqlMetadata=true`
  reports product `MySQL` -> `MySqlJdbcOutboxRecordQueries` and `schema/mysql` DDL. Harmless today (identical SQL); once
  `MariaDbJdbcOutboxRecordQueries` gets overrides, such deployments will not get them. Report in phase 7.

- 2026-11-05 user: use the latest `origin/feature/db-jdbc-optimization`. Fast-forward `a06a74c0` -> `2a2663de` ("Change order by + limit
  to min for oldestCreatedAtByPartitionsStatus query"); MariaDB effect: only `oldest_created` (MIN, no limit). DDL unchanged. The phase 2 agent
  was told to re-pin the `mdb-opt` worktree at a safe point, re-extract the catalog, hashcheck and redo its mdb-opt validation steps.
  `Q-merged/oldest_created`: new `oc-00-base` = MIN form, previous base kept as `oc-06-order-limit`. Proposals (Q-*, I-*) were made
  against `a06a74c0`; all other SQL and DDL are identical, so they stay valid. M2 compares `oc-00-base` (MIN) vs `oc-06-order-limit`.

- Phase 2 port/validation done (`results/validation/VALIDATION.md`, README "MariaDB port"). Orchestrator review: OK. 5+20 reps; `main`
  setMaxRows -> server-side `SET STATEMENT SQL_SELECT_LIMIT` (general_log proof), plans captured under the same limit; branch binds `LIMIT ?`;
  `handlers.csv` outside timing; repo-level `sql.txt` shows `MariaDbJdbcOutboxRecordQueries`; two lanes isolated; equivalence tie rule
  now verified (FAIL on wrong order). Accepted limitation: buffer-pool priming scans each variant's own indexes (same procedure, different pages).
  Observations: no engine-independent stats are created; branch `NOT EXISTS` runs as dependent subquery + subquery cache (no anti-join);
  server collation `utf8mb4_uca1400_ai_ci` (inherited by `id`, `record_key`).

- Calibration (2026-11-05): `mdb-opt` S8-S2 `count_new`/`count_partitions`/`count_partition_single` flip between two equal-cost covering
  indexes across passes (<= 0.05 ms). Early signal: `mdb-main` S8-S4 `keys_nonstrict` ~25-27 s, `oldest_created` ~14-15 s; `mdb-opt` ~1 ms.
- **Host load issue:** load average 11-13 at nearly every step start and 12.7 with no container of ours running (38 % user CPU on the
  visible CPUs, no local process): another tenant of the Proxmox host shares the physical cores. The load > 4 rerun rule cannot be met.

## User decisions

- **2026-11-05 — host load, relaxed rule (user).** Proceed despite outside load (11-13). Rerun a step only if load at step start > 18
  (1.5x calibration level) or a cell's pass-to-pass spread > 2x the noise threshold; record load for every step.
  A "no difference" result (or a cell that cannot be measured reliably here, e.g. small HOT deltas, S10 throughput, WRITE latency) may be
  **deferred** to a later run: on this host when the load drops (evening/night — check `/proc/loadavg` with nothing running, and retest
  if it is < 4), or on the user's previous, quiet machine (the PostgreSQL study host) at the end. Deferred tests are kept in the
  ledger below and re-evaluated after every round: a deferred test is **dropped** when a later candidate beats its baseline so it is
  no longer a contender (e.g. B ~ A deferred, then C beats A -> B is closed as superseded, unless B is still relevant against C).
  At the end, hand the user a minimal, current list of tests for the quiet machine.

- 2026-11-05 user idea: `completed_at`-leading index set -> round 1 `r1-e1` (orchestrator expects no P1 change, `count_statuses` worse).
  Orchestrator follow-up `r1-e2` + pack `r1-qf12`: `partition_no` before the MIN/range column + per-partition `GROUP BY` MIN (loose index scan)
  for `oldest_*`, aimed at the `S16-S2` P2 regressions of cmp pass 1 (`keys_nonstrict` 0.65 -> 1.04, `count_partitions` 0.23 -> 0.45,
  `oldest_due_nonstrict` 0.29 -> 0.42 ms, main -> branch; pass 1 only).

- 2026-11-05 user idea: replace the 4th index by `(status, partition_no)` (P3 does not matter) -> `r1-e1b` (completed_at-leading set +
  `(status, partition_no)`) and `r1-e3` (branch with i4 -> `(status, partition_no)`). Orchestrator expectation: `count_partitions` gets an
  exact multi-range seek with the unchanged branch SQL; `count_statuses` unchanged; write cost as i4.

- 2026-11-05 user idea E4: `(status, created_at, id, partition_no, next_retry_at, record_key, completed_at)`, i2,
  `(status, next_retry_at, partition_no, completed_at)`, `(status, partition_no)` with `ORDER BY ... LIMIT 1` forms for `oldest_created`
  (`oc-03-order-id`) and `oldest_due_nonstrict` (`odn-01-order`) -> round 1 `r1-e4` (branch SQL and pack `r1-qf13`).

- 2026-11-05 user idea E5: drop the outer `completed_at IS NULL` in keys_strict/nonstrict, oldest_created, oldest_due_* (valid: all five
  bind `status = NEW` in code) with indexes `(status, created_at, id, partition_no, next_retry_at, record_key)`, i2,
  `(status, next_retry_at, partition_no)`, `(status, partition_no)` -> round 1 `r1-e5`, packs `r1-qf14`/`r1-qf15` (`Q-merged` E5 forms).
  Inner `earlier.completed_at IS NULL` kept in the strict probes (it is the incompleteness test); dropping it in nonstrict is a control.

- **2026-11-07 — phase 5 done (`05-final-candidate.md`).**
  - `exp/mdb-final` in `../outbox-wt-mdb-final`: `76c51fdb` (sub-agent) + `f31bc505` (orchestrator review: KDoc named the wrong index
    — plans show i1 for `keys_*`/`oldest_created` and i3 for `oldest_due_nonstrict`; a garbled sentence in `upgrading-to-1-12.md`;
    a wrong claim in `database.md`). jdbc tests 537/0 failures, `*MariaDb*` integration 2/0, ktlint OK; after the review fix the
    MariaDB tests and ktlint were rerun (pass).
  - Change outside the MariaDB class: `JdbcOutboxRecordRepository.findOldestCreatedAtByPartitions` binds `limit` 1 and `withMaxRows(1)`
    (all dialects; their SQL has no `:limit`, catalog equal to `mdb-opt`).
  - Harness fix (orchestrator): `CatalogExtractor` wrote `maxrows: none` for `oldest_created`, so `bench` would bind `:limit` = batch
    (10) instead of 1. Now `one` when the query-package SQL contains `:limit`; `:repo` rebuilt, `mdb-final` re-extracted.
  - **Open (report, phase 7):** the JPA entity `OutboxRecordEntity` still declares `idx_outbox_record_status_completed_partition`
    (dialect-independent). Hibernate-generated DDL on MariaDB therefore gets the index behind the 256-partition collapse (R1).

- **2026-11-07 — confirm stage done (`experiments/confirm.md`); phase 4 closed (`04-exploration.md`).**
  - Equivalence PASS for `c-1` and `c-2` on S1, S2, S5, S7. No failures, no reruns, no `SPREAD>2x`.
  - Both: win against the branch, net win against main. `c-1` = `c-2` within noise on P1/P2, S10, write cost.
  - **Final set = `c-1`** (`experiments/r2/spc/ddl.sql` + `experiments/r2/forms-a/queries/`). Reason: `c-2` `oldest_created` +0.1 ms,
    `count_statuses` +6-19 %, retention without a `completed_at` index (S9: 26,000 of 500,000 removed in 2700 s). Invariant not used.
  - Isolation test: the 4th index read penalty (0.03-0.24 ms) is planning (one dive per owned partition), not execution.
  - Stop criterion met (every P1/P2 query has a justified recommendation); no round 3. Accepted differences against main: section 4
    of `04-exploration.md` (incl. S16-S2 `keys_nonstrict` 0.39 -> 0.68 ms at 62 NEW rows).
  - Next: phase 5 sub-agent (brief 07), worktree `../outbox-wt-mdb-final`, branch `exp/mdb-final` from `2a2663de`.

- **2026-11-06 — confirm stage started (user go).**
  - Spec `experiments/confirm-spec.md`: UUIDv4 only (user), runs `c-ref` / `c-1` / `c-2`, every label, two passes, S9, S10, equivalence,
    and the isolation test of the 4th-index read penalty.
  - **User: after the confirm stage, proceed automatically with the next round or phase without waiting for a decision.** The study's
    standing rules still apply: no commits or pushes to existing branches; ask the user before applying changes to the branch (phase 7);
    user decisions are needed only for hints, generated columns, schema changes and server settings.

- **2026-11-06 — round 2 done (`experiments/r2-fourth-index.md`).**
  - **Winner `spc` = `(status, partition_no, created_at)`** with both id versions.
  - **`count_partitions`:** S16-S4 7.4 ms (main 7.5, reference 106), S8-S4 29.5 (main 61), S8-S5 4.3 (main 8.7); no gain at full ownership.
  - **Write-cost price against the reference:**
    - S4 insert redo about +10-12 % and S4 completion: no difference;
    - S2 completion redo +60-95 % and S2 delete 3.1-3.7x;
    - latency and throughput: no difference;
    - index bytes +66 MB (S2) and +122 MB (S4).
  - **`sp` loses:** v4 insert redo +144 %, S10 -14 % (v4) / -8 % (v7).
  - **v7:**
    - PK -28 %, insert redo -30 %, S10 2.1x (about 915 rec/s);
    - S2 completion/retry/fail redo +45-140 % on the reference (cause not isolated);
    - the ranking does not change.
  - **Plans:** the 4th index is used only by `count_partitions` / `count_statuses`; no false estimate at 16-256 partitions.
  - **Open:**
    - HOT/MON reads +0.05-0.25 ms with either 4th index, with plans unchanged. Likely planning or dive cost for the extra index; to isolate.
    - Part B: `kn-05-head` beats `kn-00-base` at K = 10/100/1000 (0.63x / 0.80x at K = 1000; both about 0.5-0.9 s there, linear in K).
    - Part C: `forms-e5h` passes equivalence on S1, S5 and S7.
  - **Next:** the confirm stage (proposal in the report section 11), pending the user's go.

- **2026-11-06 — user decisions for round 2 and confirm.**
  - Base (a) = i1, i2, i3 + `ks-05-head`, `kn-05-head`, `oc-03-order-id`, `odn-01-order`.
  - **A 4th index is kept**: P2 reads matter, and slow `count_partitions` is not accepted. Round 2 picks the cheapest of
    `(status, partition_no)` and `(status, partition_no, created_at)`, with v4 and v7 ids.
  - **The NEW <=> `completed_at IS NULL` invariant is accepted as a known rule.** Forms that rely on it get no penalty, and "safe"
    does not break ties.
  - `kn-05-head` with non-due heads is tested (round 2 part B).
  - Next round = confirm stage with two candidates:
    - (a) + the round-2 winner;
    - `b-e5l` DDL + `forms-e5h` (`ks-11`, `kn-14`, `oc-08`, `odn-06`, `ods-09`) + the same 4th index.
  - Spec: `experiments/round2-spec.md`.

- **2026-11-06 — round 1b done (`experiments/r1b-forms.md`).** On `r1-m1d` (i1, i2, i3):
  - `oc-03-order-id` and `odn-01-order` give 0.15-0.40 ms at every label, below main everywhere, with no write cost.
  - `ks-05-head` / `kn-05-head` win at hot keys (S8-S5 0.50x / 0.11x, S5 0.18x / 0.24x) and show no difference elsewhere.
  - No better form for `oldest_due_strict`, `pages`, `count_statuses` or `count_partitions`.
  - M1 query reverts: loss. M4 (`b-m4`): identical. M5: no difference (cost is row transfer). M7: i1, i2, i3 is minimal.
  - `b-e5l` is equal to `b-base` + forms within noise (index size -3.8 %), but invariant-dependent.
  - Equivalence of the survivors: PASS on S1, S5, S7.
  - Zero-row results of the ORDER BY forms map to null via `list().firstOrNull()` in `JdbcOutboxRecordRepository` (checked).
  - Confirm list: `b-base` + `oc-03` + `odn-01` + `ks-05` + `kn-05`.
  - Open user question: base of the round-2 grid (`b-base` + confirm forms vs `b-e5l`).
  - Snapshots are one per lane and replaced by `gen`, so round 2 must regenerate per id version and check the `ids=uuidv7` marker.

- **2026-11-06 — round 2 plan (user, discussed; spec after round 1b).** `(status, partition_no)` is kept as a candidate: the
  user values P2 metrics (`count_partitions`). Hypothesis: its write cost comes from random UUIDv4 ids, because within
  `(status, partition_no, id)` inserts and completions land at random positions. Adding `created_at`
  (`(status, partition_no, created_at[, id])`) makes them per-partition appends. The upcoming release switches id generation to UUIDv7
  (column type unchanged), so **UUIDv7 is now in scope**, overriding the earlier non-goal for round 2 and the final comparison. Round 2 grid: `b-e5l` (3 idx),
  `b-e5l` + `(status, partition_no)`, `b-e5l` + `(status, partition_no, created_at)`, each with v4 and v7 ids (`HARNESS_ID_VERSION=7`).
  Cells: S2-wc, S4-wc, S10-S2-p1-unthr, `count_partitions` and P1/P2 reads on S16-S4, S8-S5, S4, S5. Separate v7 snapshots (gen must
  not overwrite the v4 snapshots in use by round 1b).

- **2026-11-06 — round 1a done (`experiments/r1a-index-sets.md`).** Collapse fixed by every set without
  `(status, completed_at, partition_no[, x])`. Winner `r1-m1d` (i1, i2, i3): P1 at/better than main everywhere, S10 450 rec/s, S4 insert
  redo at main level. `(status, partition_no)` (e3/e1b/e4/e5q) fixes `count_partitions` but costs +120 % S4 insert redo and 2-4x S2
  completion/delete redo -> not carried forward (P1 write cost > P2 benefit). ORDER BY ... LIMIT 1 forms (e4q/e5q) solve `oldest_*`
  (0.2-0.6 ms); MIN forms do not (e5c control). `r1-a1` loses `oldest_due_strict`; `r1-e2q` keeps the collapse. M8: flip at n >= 200.
  Next: round 1b on `r1-m1d` (`experiments/round1b-spec.md`).

- **2026-11-06 — phase 3 done (orchestrator).** `03-main-vs-branch.md`: branch wins at partial ownership but collapses at 256 partitions
  with a large NEW backlog (R1: B-1d `(status, completed_at, partition_no)` gets a 256-row estimate above `eq_range_index_dive_limit`;
  S5 `keys_strict` 4.7 ms -> 15 s, S4 > 60 s; S10-S2-p1-unthr 2 rec/s). Round 1 split: 1a = index sets incl. user ideas + M8 on the
  problem cells; 1b = query packs and M5 on the 1a winner DDL.

- **2026-11-05 — skip S9 in phase 3 pass 2 (user).** S9 runs in `cmp` pass 1 only; phase 6 repeats it. Pass 1 finding: main's churn hit
  the 2700 s retention cap (28 000 of 500 000 removed, 2950 s) vs the branch (500 000 removed, 311 s); main's post-churn table is larger
  (1.30M vs 0.83M COMPLETED), so its S9 P1 timings are not like-for-like. Rule confirmed: P3 is never timed (equivalence only); screen =
  P1/P2 on S8 labels, S9/S10 only at confirm/final.

- **2026-11-07 — M5 `pages` forms under S10 (user).** Round 1b timed the `pages` forms only alone, not under S10 (orchestrator M5
  gap). Short follow-up after phase 6: `experiments/m5-s10-spec.md` (`IN` vs `JSON_TABLE` vs `VALUES` on `mdb-final`, S10-S2-p4,
  S10-S4-p4). Then phase 7 regardless of the result; the gap is reported as a deviation in `99-final-report.md`.

- **2026-11-07 — after phase 7 (user).**
  1. Commit in the primary tree (`feature/db-optimalization`): everything `git add .` selects (raw phase-6 results included).
  2. `git pull -r`. If it fails (network), rebase on the already fetched `origin/feature/db-optimalization`; resolve conflicts myself.
  3. Short MySQL experiment (mysql:9.2, UUIDv7 `HARNESS_ID_VERSION=7`, code of the latest `origin/feature/db-jdbc-optimization`,
     sonnet sub-agents): `oldest_created` MIN vs `ORDER BY created_at LIMIT 1`; `oldest_due_strict` / `oldest_due_nonstrict` MIN vs
     `ORDER BY ... LIMIT 1`; key selection `NOT EXISTS` vs head form `o.id = (SELECT e.id ... ORDER BY e.created_at, e.id LIMIT 1)`.
     Question: do the MariaDB results carry over to MySQL?
  **User 2026-11-07: from here on, decide every open question myself (also rebase conflicts: resolve them); never push.** Applying the
  final changes to `feature/db-jdbc-optimization`: done by me after phase 7 as uncommitted changes in a worktree of that branch (no
  commit, no push); every such decision is logged here and in the final report.

- **2026-11-07 — phase 6 done (`results/final/SUMMARY.md`).** 0 FAIL, 1 WARN (S5 `keys_nonstrict` final vs main, created_at tie
  permutation, equal key sets). One spread rerun (`mdb-main` `oldest_due_nonstrict` S4/S4-b100: 75-82 s, one pass 411 ms, plan flip
  unexplained). All conclusions hold at 2G. New observation: S4-wc `upd_retry` redo +293 % vs main for both `mdb-opt` and `mdb-final`
  (i1 and i3 contain `next_retry_at`), S2-wc retry +15 %. M5/S10 follow-up started.
- **2026-11-07 — the branch moved (orchestrator decision).** `origin/feature/db-jdbc-optimization` is now `4037faa5` (was `2a2663de`).
  MariaDB/MySQL there: c-2-like DDL with `(status, partition_no)` as 4th index, head forms and `ORDER BY ... LIMIT` without
  `completed_at`; JPA entity uses the same indexes (the `status_completed_partition` finding is gone); MySQL class has the same
  overrides as MariaDB (the dialect-detection finding is now harmless for `keys_*`). Decision (user delegated decisions): measure
  `mdb-final` against `mdb-opt2` = `4037faa5` once before phase 7 (`experiments/opt2-spec.md`), after the M5/S10 follow-up.

- **2026-11-07 — `mdb-opt2` vs final done (`experiments/opt2-vs-final.md`).** Reads equal; final lower redo (S4-wc insert -53 %, S2-wc
  completion -20 %, retry -14 %, delete -25 %); retention: opt2 18,000 of 500,000 in 2700 s, final all in 358 s. Verdict: final.
- **2026-11-07 — phase 7 done (`99-final-report.md`).** Final changes applied (orchestrator decision, user delegated) as uncommitted
  changes in `/home/azamojski/outbox-wt-jdbc-opt` (`feature/db-jdbc-optimization` at `4037faa5`, local tracking branch created):
  MariaDB DDL, `MariaDbJdbcOutboxRecordQueries` (5 statements), tests, docs. jdbc tests 538/0, `*MariaDb*` 2/0, ktlint clean. Orchestrator
  review fixed the MariaDB snapshot upgrade step (same-name indexes, `status_completed_partition`) and the retention sentence in
  `database.md`. Quiet-machine list: report section 5.

- **2026-11-07 — study committed and rebased.** `f4c0ccf5` -> after `git pull -r` `412f160f` on `feature/db-optimalization` (clean
  rebase; not pushed). The remote brought `docs/db-postgresql/` (renamed from `docs/db-sql/`), removed `docs/db/`, and an independent
  MySQL study in `docs/db-mysql/`. Isolation rule extended for sub-agents: no reading of `docs/db-postgresql/` or `docs/db-mysql/`.
- **2026-11-07 — MySQL check started (user request).** `mysql-check/spec.md`: mysql:9.2, UUIDv7, code `4037faa5` (MySQL DDL and
  catalog), forms A (branch) vs B (MIN / `NOT EXISTS`) for `oldest_created`, `oldest_due_*`, `keys_*`. Kept in
  `docs/db-mariadb/mysql-check/` so it never touches `docs/db-mysql/`.

- **2026-11-08 — MySQL check done (`mysql-check/REPORT.md`).** MySQL 9.2, v7, `4037faa5`: ORDER BY LIMIT 1 > MIN carries over;
  ordered `oldest_due_strict` kept carries over (but seconds on MySQL at S4-type data); head form for `keys_*` reverses (S8-S5
  `keys_strict` 31.8 s head vs 227 ms `NOT EXISTS`): `4037faa5`'s MySQL head forms regress at hot keys. Added to `99-final-report.md`
  section 5. MySQL container stopped (volume kept). Harness: MySQL support (`DIALECT`), binlog purge needed once (disk).

## Deferred tests (quiet host / quiet time)

| Id | What | Why deferred | Relevant against | Status |
|---|---|---|---|---|

## Running sub-agents

- Phase 3 measure sub-agent (`cmp`): **done 2026-11-06**, `results/cmp/SUMMARY.md` (+ `rerun1/`); harness additions: `run-cells.sh`
  label mapping for S16-*/S10-*, `bench churn --retention-max-s`. Report `03-main-vs-branch.md` (orchestrator).
- Round 1a experiment sub-agent: done 2026-11-06 (harness: `bench --pages-keys-file`).
- Round 1b experiment sub-agent: done 2026-11-06 (harness: `:recordKeysJson`, `:recordKeysRows`, UNION ALL expansion).
- Round 2 experiment sub-agent: done 2026-11-06 (no harness code change).
- Confirm sub-agent: done 2026-11-07, `experiments/confirm.md` (harness: `bench plantime`).
- Phase 5 finalize sub-agent: done 2026-11-07.
- Phase 6 final-comparison sub-agent: done 2026-11-07, `results/final/SUMMARY.md`.
- M5/S10 follow-up sub-agent: done 2026-11-07, `experiments/m5-s10.md` (harness: `Ctx.bindKeyForms` refreshes JSON/VALUES key params per poll). Verdict: `IN` stays; JSON_TABLE / VALUES no throughput difference, `pages` p50 +0.1-0.16 ms.
- `mdb-opt2` vs final sub-agent: done 2026-11-07.
- Apply sub-agent: done 2026-11-07 (`/home/azamojski/outbox-wt-jdbc-opt`).
- Phase 4a query proposers started early (no measuring, no results access): `Q-indep` (independent), `Q-pgx` (PostgreSQL transfer).
  - `Q-pgx` done: 40 forms in `experiments/Q-pgx/` (all parse on 12.3.3, toy-table result check). Merge notes: `cs_incomplete`/`cp_incomplete`
    add `completed_at IS NULL` to queries whose status parameter may be COMPLETED -> contract-invalid unless restricted (check callers at merge);
    `pg_json_straight` needs a code change; `*_force`/`*_ignore` = index hints (user decision); `ks_window`/`kn_window` assume one partition per key.
  - `Q-indep` done: 41 forms in `experiments/Q-indep/` (3 fixed forms syntax-unverified).
  - Decision: `count_partitions`/`count_statuses` forms with `completed_at IS NULL` are class `status-conditional` (valid only if the code
    selects them when COMPLETED is not requested; `countRecordsByPartitions` is public and accepts any status, sole caller passes NEW).
  - Merge done: `experiments/Q-merged/` (60 forms + `*-00-base`/`*-00-main`, `INDEX.md`; classes ok 48, hint 13, code 6,
    status-conditional 6, mariadb-only 1; none invalid). Notes: `kn-08`/`kn-09` GROUP BY forms may differ at the LIMIT boundary (equivalence decides);
    some forms bind a collection twice (harness support to verify).
  - `I-A` done: sets A1 (i1+i2), A2 (n1 `(status,completed_at,created_at)` + n2 `(record_key,completed_at,created_at)`, implicit id),
    A3 (i1,i2,i3), A4 (d1 `(status,completed_at,next_retry_at,partition_no,created_at,id,record_key)` + i2), A5 (i1,i2,d1), A6 (= branch).
    i1..i4 = branch B-1a..B-1d. All DDLs apply on 12.3.3.
  - `I-B` done: B1 (i1,i2) = A1; B2 (i1,i2,i3) = A3; B3 (i1,i2,i4) = M1 "drop B-1c"; B4 (i2 + IP `(partition_no,status,completed_at,
    next_retry_at,created_at,id,record_key)`); B5 (i1,i2,IP); B6 = branch. Flagged (user decision, not in DDLs): generated `is_open`
    column, smaller `id` type (non-goal).
  - Distinct DDL candidates for round 1: A1/B1, A2, A3/B2, A4, A5, B3, B4, B5 (+ M1 drops of B-1a, B-1b, B-1d).
  Decision: proposals do not depend on measurements, so they run in parallel with phase 2; phase 3 still precedes any experiment.

## Next step

All done (phases 0-7, M5/S10, `mdb-opt2`, MySQL check). Final changes wait uncommitted in `/home/azamojski/outbox-wt-jdbc-opt` for the
user. Never push.
