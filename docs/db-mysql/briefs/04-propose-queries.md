# Brief 04 — Propose query forms (no measuring)

You are a sub-agent (Sonnet) in a **fresh context**. You propose SQL; you do not run benchmarks. The orchestrator tells you which
queries to cover and whether you are the **independent** proposer or the **PostgreSQL-transfer** proposer.

## Isolation rule

See `docs/db-mysql/briefs/00-isolation-rule.md` (pasted into your prompt).
- **Independent proposer:** you may **not** read `docs/db-mysql/reference/postgres-rationale.md`, nor any `results/` or
  `experiments/` file. Work from the contract and the data shape only.
- **PostgreSQL-transfer proposer:** you **must** read `docs/db-mysql/reference/postgres-rationale.md` and propose MySQL
  forms of its ideas. Its citations point into `docs/db-postgresql/`: do **not** open them (the isolation rule still applies). You may not
  read MySQL results.

## Inputs

`docs/db-mysql/01-usage-map.md` (contract, semantics, current SQL), `docs/db-mysql/02-scenarios.md` (data shapes, parameters),
the KDoc of `OutboxRecordRepository`, `docs/adr/0005-...md` in the branch worktree, the MySQL version in `STATE.md`, MySQL docs.

## Task

For each assigned P1/P2 query id (`keys_strict`, `keys_nonstrict`, `pages`, `count_statuses`, `count_partitions`,
`oldest_created`, `oldest_due_strict`, `oldest_due_nonstrict`; writes are not rewritten), propose **3–6 semantically equivalent
forms** that a MySQL 8.4 optimizer might execute well, for example: `NOT EXISTS` vs `LEFT JOIN ... IS NULL` anti-join vs
window functions vs `GROUP BY` + `MIN`; `ORDER BY ... LIMIT` vs `MIN`/`MAX`; `IN (...)` vs `JSON_TABLE`/`VALUES ROW(...)` derived
key table vs `LATERAL` derived tables vs `UNION ALL` per partition or per key; CTEs; `STRAIGHT_JOIN`; index hints (`FORCE INDEX`,
`USE INDEX`, `/*+ INDEX(...) */`, `/*+ NO_INDEX(...) */` — flag them as high risk, they fail or silently change behaviour on a
schema without the named index); other MySQL optimizer hints (`JOIN_ORDER`, `SEMIJOIN(...)`/`NO_SEMIJOIN`, `SKIP_SCAN`,
`NO_RANGE_OPTIMIZATION`, `BKA`, `NO_HASH_JOIN`/`HASH_JOIN`-era switches, `SET_VAR(...)` — flag `SET_VAR` of non-default settings as a
user decision); how MySQL ≥ 8.0.17 turns `NOT EXISTS`/`NOT IN` into antijoins and when it does not (e.g. correlated conditions it
cannot transform, `LIMIT` in the subquery); ranges that let the optimizer use index order (`created_at <=` before the tie-break,
`completed_at IS NULL` repeated); splitting by status; descending index order.

Every form must keep the contract exactly: same rows, same order where the KDoc defines one, strict per-key ordering,
`completed_at IS NULL` only on NEW/FAILED queries (never on queries that may count `COMPLETED`), the same parameters (`:partitions`,
`:status`, `:now`, `:limit`, `:recordKeys`, `:statuses`). Rejected by the user, do not propose: capping the strict key candidates
before the anti-join (it can return fewer keys than `:limit` when candidates share a key).

Write `docs/db-mysql/experiments/Q-<proposer>/` with one directory per query id containing `<form-id>.sql` (harness catalog
format: copy the header lines from `harness/catalog/my-opt/mysql/<id>.sql`, then the SQL with named parameters) and a
`forms.md`: per form the idea, why it might be faster on InnoDB/MySQL, which access path it needs (column order, covering
columns), semantic risks, and portability to MariaDB (a MariaDB server reached through MySQL Connector/J receives the MySQL
statements: say whether the form parses and returns the same rows on MariaDB 11/12; `VALUES ROW(...)`, `LATERAL` and some hints do
not exist there). **Do not propose indexes** beyond naming the access path each form needs.

Return a summary of ≤300 words: forms per query, the 3 most promising forms with reasons, output path.
