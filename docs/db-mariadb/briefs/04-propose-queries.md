# Brief 04 — Propose query forms (no measuring)

You are a sub-agent (Sonnet) in a **fresh context**. You propose SQL; you do not run benchmarks. The orchestrator tells you which
queries to cover and whether you are the **independent** proposer or the **PostgreSQL-transfer** proposer.

## Isolation rule

See `docs/db-mariadb/briefs/00-isolation-rule.md` (pasted into your prompt).
- **Independent proposer:** you may **not** read `docs/db-mariadb/reference/postgres-rationale.md`, nor any `results/` or
  `experiments/` file. Work from the contract and the data shape only.
- **PostgreSQL-transfer proposer:** you **must** read `docs/db-mariadb/reference/postgres-rationale.md` and propose MariaDB
  forms of its ideas. Its citations point into `docs/db-sql/`: do **not** open them (the isolation rule still applies). You may not
  read MariaDB results.

## Inputs

`docs/db-mariadb/01-usage-map.md` (contract, semantics, current SQL), `docs/db-mariadb/02-scenarios.md` (data shapes, parameters),
the KDoc of `OutboxRecordRepository`, `docs/adr/0005-...md` in the branch worktree, the MariaDB version in `STATE.md`, MariaDB docs.

## Task

For each assigned P1/P2 query id (`keys_strict`, `keys_nonstrict`, `pages`, `count_statuses`, `count_partitions`,
`oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`; writes are not rewritten), propose **3–6 semantically equivalent
forms** that a MariaDB 12 optimizer might execute well, for example: `NOT EXISTS` vs `LEFT JOIN ... IS NULL` anti-join vs
window functions vs `GROUP BY` + `MIN`; `ORDER BY ... LIMIT` vs `MIN`/`MAX`; `IN (...)` vs `JSON_TABLE`/`VALUES` derived key table
vs `UNION ALL` per partition or per key; `STRAIGHT_JOIN`; index hints (`FORCE INDEX`, `USE INDEX` — flag them as high risk, they
fail on a schema without the named index); optimizer hints MariaDB supports; ranges that let the optimizer use index order
(`created_at <=` before the tie-break, `completed_at IS NULL` repeated); splitting by status.

Every form must keep the contract exactly: same rows, same order where the KDoc defines one, strict per-key ordering,
`completed_at IS NULL` only on NEW/FAILED queries (never on queries that may count `COMPLETED`), the same parameters (`:partitions`,
`:status`, `:now`, `:limit`, `:recordKeys`, `:statuses`). Rejected by the user, do not propose: capping the strict key candidates
before the anti-join (it can return fewer keys than `:limit` when candidates share a key).

Write `docs/db-mariadb/experiments/Q-<proposer>/` with one directory per query id containing `<form-id>.sql` (harness catalog
format: copy the header lines from `harness/catalog/mdb-opt/mariadb/<id>.sql`, then the SQL with named parameters) and a
`forms.md`: per form the idea, why it might be faster on InnoDB/MariaDB, which access path it needs (column order, covering
columns), semantic risks, and portability to MySQL. **Do not propose indexes** beyond naming the access path each form needs.

Return a summary of ≤300 words: forms per query, the 3 most promising forms with reasons, output path.
