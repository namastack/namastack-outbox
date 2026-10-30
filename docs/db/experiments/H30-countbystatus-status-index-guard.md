# H30: countByStatus(COMPLETED) needs a small status-leading index (guard for idx_status changes)
Status: measured
Group: G4 (metrics). Iteration 1.
Target queries: `countByStatus` (NEW / FAILED / COMPLETED). Regression guard for H31, H40 (replace variants) and
for any G1/Phase-2 proposal that drops or modifies `idx_outbox_record_status`.

## Change

No new DDL of its own. H30 measures `countByStatus` under the index sets that other hypotheses propose, plus two
visibility-map states, to put a number on what a change to `idx_outbox_record_status` costs this query.

| Index set | DDL (applied with `--ddl`) |
|---|---|
| base | repository DDL |
| S-created | `H31/indexes.sql` (`status` -> `(status, created_at)`) |
| S-completed | `H40/indexes-replace.sql` (`status` -> `(status, completed_at)`) |
| S-none | `H30/indexes-drop-status.sql` (only `status_retry (status, next_retry_at)` and `partition_status_retry` remain) |

VM states: `H30/vacuum.sql` (all-visible) and `H30/clear-vm.sql` (rolled-back DELETE of all COMPLETED rows:
every COMPLETED page loses its all-visible bit).

Informational query rewrite (not equivalent at the API level; needs a new SPI method):
`H30/queries/countByStatus_grouped.sql`:

```sql
SELECT status, COUNT(*) FROM outbox_record GROUP BY status ORDER BY status;
```

## Findings from the code and baseline

- Callers (main code), all with `status` in {NEW, FAILED, COMPLETED}:
  - `namastack-outbox-observability` `OutboxMetricsCollector.collectRecordCounts()`: iterates
    `OutboxRecordStatus.entries` (NEW, COMPLETED, FAILED) on its own scheduler, `scheduleWithFixedDelay`, default
    `refresh-interval` 15 s (`OutboxMetricsProperties`). So 3 calls every 15 s plus the query time, per instance.
  - Deprecated `namastack-outbox-metrics` `OutboxRecordMetricsMeterBinder`: one Micrometer gauge per status that
    calls `countByStatus` on **every gauge read** (every scrape of every registry, no caching).
  - `OutboxInstanceRegistry.getActiveInstanceCount()` calls `instanceRepository.countByStatus(ACTIVE)` on
    `outbox_instance`, not `outbox_record` (out of scope).
- Baseline (`01-baseline.md`, custom, all-visible): COMPLETED A 23.9 ms / B 19.6 / D 22.8 / E 23.4 (794 buffers,
  parallel index-only scan of the 6.4 MB deduplicated `status` index, ~1M TIDs); NEW 0.33 / 9.85 (B) ; FAILED 0.04.
- **Correction to the baseline:** the `countByStatus-statusCOMPLETED-generic.txt` and `-costs.txt` files (82 ms A,
  67 ms B, ~91.8k buffers) are not a generic-plan effect. They show `Heap Fetches: 993,800`: those runs came after
  the rolled-back `deleteByStatus(COMPLETED)` / `deleteByIds` files, which cleared the visibility map. The plan is
  identical. In production the same happens to pages touched by the NEW -> COMPLETED updates until autovacuum
  runs, so the realistic range is 24 ms (all-visible) to ~80 ms (VM cleared) at 1M COMPLETED rows.
- The count is linear in the number of COMPLETED rows. No index can make `COUNT(*)` of ~1M qualifying rows
  sublinear in Postgres; a partial index does not help either (it would hold all COMPLETED rows).
  `COUNT(*) total - COUNT(NEW) - COUNT(FAILED)` still needs a full count. So **no DB-level improvement exists**;
  the work here is to keep the current cost when other hypotheses change the `status` index.

## Rationale / expected plan

- base: Finalize Aggregate -> Gather -> Partial Aggregate -> Parallel Index Only Scan `idx_outbox_record_status`.
- S-created / S-completed: same shape on the composite index. COMPLETED entries are no longer deduplicated
  (unique second column), so the scan reads about 40-45 MB (~5.5k pages) instead of 6.4 MB (794 pages).
  The executor work per TID is the same (index-only scans return each TID individually even from posting lists),
  so I expect +10-40% (24 -> ~27-35 ms all-visible). NEW and FAILED counts stay sub-ms (NEW in S-completed keeps
  equal keys `(NEW, NULL)` and stays deduplicated; in S-created it does not, B: 200k entries ~ 1.1k pages).
- S-none: the planner falls back to `status_retry` (45 MB, same as the composite case) or, with Postgres 18 skip
  scan, `partition_status_retry` (68 MB, 256 skip searches). Expected roughly like S-created, worse for NEW in B.
- VM cleared: + ~1M heap fetches (~92k buffers) for every variant: ~70-85 ms, which dwarfs the index differences.
- `countByStatus_grouped`: one parallel index-only scan of the whole index, about the cost of COMPLETED alone
  (saves the NEW + FAILED calls: ~0.4 ms in A, ~10 ms in B). Not worth an SPI change on its own.

## Semantic equivalence argument

The SQL is unchanged; only indexes differ. Every variant must return the same count (trivial check:
`compare-results.sh <c> harness/queries/countByStatus.sql experiments/H30/queries/countByStatus.sql --var status=X`
across index sets is identical by construction; instead record the three counts per index set).

## Portability

- MySQL/MariaDB (InnoDB): `COUNT(*)` with `status = ?` uses the smallest covering secondary index; a composite
  `(status, x)` is larger than `(status)` (no deduplication in InnoDB either way, so the ratio is smaller than in Postgres).
- SQL Server / Oracle: index (fast full) scan of the narrowest index with leading `status`; same linear cost.
- JPA: same SQL (`select count(o) ... where o.status = :status`). Mongo: `count` with `{status}` uses `status`
  (`@Indexed`) as a COUNT_SCAN; a compound `{status, completedAt}` also supports COUNT_SCAN.

## Experiment plan

1. For scenarios A and B (D optional): start one container per index set (`start-postgres.sh H30-<set> A --ddl ...`).
2. `run-explain.sh -c <c> -s H30-<set>-A -q experiments/H30/queries` (custom + `--costs`) after `vacuum.sql`.
3. `psql < clear-vm.sql`, then rerun step 2 with `--tag vmcleared`. Then `vacuum.sql` again.
4. Record per set: COMPLETED/NEW/FAILED median ms, buffers, heap fetches, index size (`sizes.txt`).
5. Report the delta of each replacement against base; Phase 2 uses it to price dropping `idx_status`.

## Open questions (behavioural, out of scope for index work)

- Counting COMPLETED on every refresh is the only reason these queries grow with history. Options for the
  maintainers: drop the COMPLETED gauge, refresh it on a much longer interval, or report an estimate
  (`pg_class.reltuples` x fraction) for COMPLETED. All change the meaning or freshness of
  `outbox.records.count{status=completed}`.
- The deprecated metrics binder runs `countByStatus` uncached on every scrape and per registry; with both metric
  modules enabled the table is counted twice per period.
- A `countByStatuses()` SPI method would turn 3 calls into 1 scan (`countByStatus_grouped.sql`).

Setup: Postgres 18, scenarios A/B/D (1M rows, tmpfs, shared_buffers 1GB), every state measured IN PLACE on one container per scenario on top of **H08a**
(`experiments/H08/indexes.sql`), `VACUUM ANALYZE` after each DDL. Driver: `experiments/G4G5-iter1/run-scenario.sh` (states h08a, h32psr, h32ii, h32ii-nostatus,
h31a-nopsr, h31a, h31b, h40add, h40repl, h07), equivalence `eq.sh`, write cost `run-writecost.sh`, extras `run-extra.sh`. Raw: `results/G4G5-{A,B,D}/`,
`results/G4G5-{A,B}-g5/` (cleanup), `results/G4G5-{A,B}-hot/` (hot-path regression subset), `results/G4G5x-{A,B}/`, `results/G4G5-wc-A/`, `results/G4G5-eq-*.txt`.
Baseline = `01-baseline.md`; "H08a ctl" = control remeasured (identical to baseline for these queries).
Format: median ms (shared buffers). Raw `.txt` plans of the three H32 queries were overwritten by the +INC runs (same file name and tag); `summary.tsv` has both rows (first = original SQL, second = +INC).

### countByStatus (SQL unchanged; index states)
| status | scen | baseline | H08a ctl | no `idx_status` (h32ii-nostatus) | `(status,created_at)` replace (h31a) | `(status,completed_at)` replace (h40repl) | `(status,completed_at)` add (h40add) |
|---|---|---|---|---|---|---|---|
| COMPLETED custom | A | 23.9 (794) | 23.8 (794) | 42.4 (5,668) | 74.8 (5,689) [non-parallel plan; 38.8 generic/parallel] | 74.1 (5,689) [38.2 generic] | 22.6 (862) |
| COMPLETED custom | B | 19.6 (642) | 19.3 (642) | 37.3 (4,932) | 32.9 (3,952) | 33.4 (3,952) | 19.1 (696) |
| COMPLETED custom | D | 22.8 (783) | 23.2 (783) | 41.8 (5,546) | 74.2 (5,677) [38.0 generic] | 73.3 (5,677) | 22.2 (850) |
| NEW custom | B | 9.85 (161) | 9.64 (161) | 11.3 (775) | 11.1 (775) | 8.5 (179) | 9.95 (174) |
| FAILED custom | A/B/D | 0.04 | 0.03-0.04 | 0.03-0.05 | 0.05 | 0.04 | 0.04 |

Visibility-map caveat (`H30/clear-vm.sql`: rolled-back DELETE of all COMPLETED rows clears the all-visible bits, then the same query; afterwards `vacuum.sql`):

| COMPLETED | A | B | D |
|---|---|---|---|
| H08a (deduplicated `idx_status`) | 83.7 (91,826) | 67.2 (73,913) | 82.9 (90,751) |
| no `idx_status` | 98.3 (101,056) | - | - |
| `(status,created_at)` replace | **446 (980,047)** | 83.0 (80,113) | **444 (970,164)** |
| `(status,completed_at)` replace | **445 (980,047)** | 82.7 (80,085) | **447 (970,164)** |
| `(status,completed_at)` add (`idx_status` kept) | 82.9 | - | - |

Why: replacing the single-column index (deduplicated posting lists, 6 MB, 794 pages) with a composite whose COMPLETED entries are unique (39 MB, ~5.7k pages) makes the all-visible case 1.6-3x slower
(the planner also sometimes picks a non-parallel plan: 74 ms vs 38 ms parallel) and the VM-cleared case ~5.4x slower in A/D (980k buffer pins instead of 91k).
Keeping `idx_status` and ADDING the composite leaves `countByStatus` untouched. Dropping `idx_status` without replacement: COMPLETED +60-80% (falls back to `status_retry`, 45 MB), NEW(B) +17%.
`countByStatus_grouped.sql` not measured (needs an SPI change; saves only the NEW+FAILED calls, ~0.4 ms A / ~10 ms B). No index makes COUNT(*) of ~1M COMPLETED rows sublinear.

## Equivalence check
SQL unchanged. Counts (NEW/FAILED/COMPLETED) and min(created_at) identical before and after replacing `idx_status` with `(status,created_at)`: last lines of `results/G4G5-eq-{A,B,D,edge}.txt`. PASS.

## Regression
Write cost: see H31/H32/H40. Dropping `idx_status` alone: WAL ins 2,426 B (-2.0% vs H08a) / upd 3,118 (-2.2%).

## Experimenter note
Replacing `idx_status` is the expensive move for the periodic `countByStatus(COMPLETED)` (every 15 s per instance; deprecated binder: every scrape): +60% to +200% when all-visible, up to 5.4x with a dirty VM.
The benefit those replacements are after (oldest-created, cleanup) is available without touching `idx_status` (H32 predicate, H41 real LIMIT + added index).

## Decision + reasoning


## Orchestrator decision
Rejected: keep `idx_outbox_record_status` unchanged (countByStatus(COMPLETED) penalty; oldest-created solved by H32). (ledger: `../STATE.md`; final report: `../99-final-report.md`)
