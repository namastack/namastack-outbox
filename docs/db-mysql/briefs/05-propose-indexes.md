# Brief 05 — Propose index sets for query forms (no measuring)

You are a sub-agent (Sonnet) in a **fresh context**. You propose InnoDB index sets; you do not run benchmarks.

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md` (pasted into your prompt). You may **not** read
`docs/db-mysql/reference/postgres-rationale.md`, the `forms.md` rationales of the query proposers, or any `results/` file —
only the SQL files you are given, so your view of the indexes is not anchored on anyone's reasoning.

## Inputs

The SQL forms (`docs/db-mysql/experiments/Q-*/<query>/<form>.sql`, the orchestrator lists the ones to cover),
`docs/db-mysql/01-usage-map.md` sections 1, 2 and 6 (table, semantics, write amplification), `docs/db-mysql/02-scenarios.md`
(data shapes: COMPLETED share, NEW backlog, hot keys, FAILED share, partial ownership), ADR-0005 (index rule), MySQL docs.

## Task

1. **Per form:** the minimal index (column order, covering columns) that gives it the best InnoDB access path, with the expected
   plan (access type, key parts used, ordered scan or filesort, rows examined vs returned on S2/S4/S5/S7 at partial ownership).
   Remember that every InnoDB secondary entry carries the PK `id` (`VARCHAR(255)`), that there are no partial indexes, that NULL
   keys are indexed, and that `completed_at` as a key column can make `IS NULL` a range that skips the COMPLETED history. MySQL
   features you may use: descending key parts, index skip scan, index merge, invisible indexes (read-only screening aid: an
   invisible index is still maintained, so it does not save write cost).
2. **Index sets:** 4–8 complete sets for the whole P1+P2 workload (one form chosen per query), from minimal (fewest indexes, cheapest
   writes) to read-optimal. For each set: indexes (DDL for `outbox-tables.sql`), which form each query uses, estimated write cost
   (indexes maintained per `insert`, `upd_complete`, `upd_retry`, `upd_fail`, `delete_by_id`; entry size), and which P2 queries get
   slower in exchange.
3. Flag any idea that needs a **schema change beyond indexes** (generated/virtual columns, functional indexes — MySQL implements
   them as hidden virtual columns —, column type changes): allowed to test but needs a user decision before adoption (ADR-0005
   non-goal for column types).
4. Note for each set whether its DDL also works on MariaDB (MariaDB servers reached through MySQL Connector/J use `schema/mysql`).

Write `docs/db-mysql/experiments/I-<run>/proposal.md` and one `<set-id>.ddl.sql` per set (a full MySQL `outbox-tables.sql`
override usable with the harness `--ddl`). Return a summary of ≤300 words with the sets and the output path.
