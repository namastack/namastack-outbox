# Brief 06 — One experiment or screening round

You are a sub-agent (Sonnet). The orchestrator gives you: experiment or round id, hypothesis, base variant, the change (DDL override,
query overlays, or a code change), lane(s), stage (`screen`, `confirm`, `validate`), labels, comparison targets (normally
`mdb-main` and `mdb-opt`), and the report path.

## Isolation rule

See `docs/db-mariadb/briefs/00-isolation-rule.md` (pasted into your prompt). You may not read the reference file unless the
orchestrator explicitly allows it for this experiment.

## Task

1. **Change.**
   - Index-only: a DDL override `docs/db-mariadb/experiments/<id>/ddl.sql` (full MariaDB `outbox-tables.sql`), used with `--ddl`.
   - Query-only: overlays in `docs/db-mariadb/experiments/<id>/queries/<query id>.sql`, used with `--queries` and
     `--catalog-variant <base>`.
   - Code change (only for the final candidate or when overlays cannot express it): worktree `../outbox-wt-mdb-exp-<id>` on a new
     local branch `exp/mdb-<id>` from the base, `bin/extract-catalog.sh`, and `./gradlew --init-script <harness>/gradle/jdk25.init.gradle.kts :namastack-outbox-jdbc:test` there.
   Keep the repository contract (KDoc of `OutboxRecordRepository`), strict per-key ordering, status/retry semantics and partition
   ownership. Never weaken a test to make it pass.
2. **Correctness.** Equivalence against the base on `S1` and on the scenario most at risk (`S5` for ordering and hot keys, `S7` for
   FAILED). Required before `confirm`. A FAIL ends the experiment as `invalid`.
3. **Measure** with `harness/bin/run-cells.sh`: base and candidate back to back **in the same lane on the same snapshot**. Stage
   rules: `screen` = one pass on the screen labels; `confirm` = all labels, plus `S2-wc`/`S4-wc` when DDL changed, plus S10 when a
   P1 read changed, plus S9 when DDL changed; `validate` = two passes, reversed order, container restart between, solo.
4. **Report** `docs/db-mariadb/experiments/<id>-<slug>.md`:
   - Hypothesis, base, exact change (DDL/diff/overlay files), branch name if any.
   - Results: query × label, base vs candidate vs targets, relative change, noise threshold from `STATE.md`; partial-ownership
     labels first. Differences below the threshold are "no difference".
   - Plans before/after for the affected queries (from `plans/*.summary.txt`), rows examined (`handlers.csv`).
   - Write cost: redo bytes/write, pages written/write, insert/update p50/p95, per-index and total index size, index count.
   - Verdict: `win` / `no difference` / `loss` / `mixed (explain)` / `invalid`, judged by the ADR-0005 rule (index justified only
     by P1/P2, read benefit must outweigh write cost; P3 regressions reported but not blocking).
   - Risks (index hints, schema changes, MySQL portability) and follow-up ideas.

Return a summary of ≤300 words: verdict, key numbers, report path.
