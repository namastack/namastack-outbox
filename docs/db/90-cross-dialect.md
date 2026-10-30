# Cross-dialect validation (Phase 3)

Postgres decided what was accepted (set S1, `experiments/H60-consolidated-set.md`). This page checks whether the accepted changes
carry over to the other databases. Details, plans and scripts are in the per-database sections:
[mysql](90-cross-dialect/mysql.md), [mariadb](90-cross-dialect/mariadb.md), [sqlserver](90-cross-dialect/sqlserver.md),
[oracle](90-cross-dialect/oracle.md), [jpa](90-cross-dialect/jpa.md), [mongodb](90-cross-dialect/mongodb.md). Raw output is in `phase3/`.

## Scale
| DB | Version | Rows | Note |
|---|---|---|---|
| MySQL | 9.2 | 1M | A/B |
| MariaDB | 12.3.3 | 1M | A/B |
| SQL Server | 2022 (16.0.4295) | 200k (10k keys × 20) | host disk space |
| Oracle | XE 18c | 200k | host disk space; slow disk, noisy write cost |
| MongoDB | 9.0.4 (test image) | 200k documents | |
| JPA | Hibernate SQL captured on H2 | n/a | plans taken from the equivalent Postgres SQL (H60) |

## Summary
The query rewrite, a head-of-key form that scans incomplete rows in `(created_at, id)` order and stops at a real limit, is portable.
It stops early on every database. The outer index that supports it differs per database:

| DB | Recommended outer index | Query adaptation | Key query B, before → after (ms) | Cleanup select B (ms) |
|---|---|---|---|---|
| Postgres | partial `(created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key) WHERE completed_at IS NULL` + extended statistics | `LIMIT n` | 265 → 0.21 | 159 → 75 (real LIMIT, no index) |
| MySQL | non-partial `(completed_at, created_at, id, partition_no, status, next_retry_at, record_key)` (`id` explicit) | `LIMIT n`; optimizer picks partition index + sort in A (7 ms, `FORCE INDEX` 1.5) | 510 → 2.4 | 4534 → 16 |
| MariaDB | same as MySQL | `LIMIT n`; early stop without hints | 308 → 1.6 | 1454 → 21 |
| SQL Server | filtered `(created_at, id) INCLUDE (partition_no, status, next_retry_at, record_key, completed_at) WHERE completed_at IS NULL` | `TOP (n)` or `OFFSET 0 ROWS FETCH NEXT n ROWS ONLY`; filtered index used under `sp_executesql` | 38.6 → 1.0 | 122 (index C optional: → 11) |
| Oracle | status-leading `(status, created_at, id, partition_no, next_retry_at, record_key)` | `ORDER BY SYS_EXTRACT_UTC(created_at), id FETCH FIRST n ROWS ONLY`; oldest-due needs `FIRST_ROWS(1)` | 129.5 → 1.0 | 1640 (index C optional: → 100) |
| MongoDB | partial `{createdAt, _id, partitionNo, status, nextRetryAt, recordKey}` + partial `{recordKey, createdAt, _id}`, both `partialFilterExpression {completedAt: null}` | head-of-key pipeline (`$match`, `$sort`, `$lookup` probe, `$limit`) | 76 → 4 | n/a |

Findings that matter for the implementation:
- **Dropping `record_key_created`** is safe on every database; nothing regressed. MongoDB can also drop `fifo_pipeline_idx` and
  `record_key_completed_created_idx` (net −24% index size).
- **Pages query.** The LATERAL "Lon" form is a Postgres-only gain. MySQL supports LATERAL but gains less. MariaDB has no LATERAL.
  SQL Server `CROSS APPLY (VALUES …)` silently dropped one key's rows once the list had 299 or more keys on 2022 16.0.4295, and was not faster.
  Oracle `CROSS APPLY` was no faster and fails at 1000 keys (ORA-00939). Every other database keeps the IN form.
- **IN-list limits.** Oracle allows at most 1000 expressions (ORA-01795 at 1001), so a 1000-key page is exactly at the limit. SQL Server allows 2100
  parameters, and 1000 keys + 256 partitions fits.
- **Oracle `TIMESTAMP WITH TIME ZONE`.** Indexes on these columns store UTC, so `ORDER BY created_at` never uses index order and never stops early
  (130 ms in B even with a perfect index). The query must order by `SYS_EXTRACT_UTC(created_at)`. JPQL cannot express this, so JPA on Oracle does not get the early stop.
- **The status-based "incomplete" predicate (user idea, H09)** is the best form on Oracle. On MySQL and MariaDB it ties with the `completed_at`-leading index
  for key queries, but costs more writes and needs an extra `(status, completed_at)` index for cleanup (2–4 s without it). On MariaDB it is better for the
  1000-key pages query (75 vs 159 ms).
- **Filter-off key query on SQL Server** regresses with the rewrite (parallel hash anti join: B 38 ms, A 70 ms vs 18.7/1.6 today). An index hint on
  the probe fixes it (1.2 ms), or the current query can stay for this non-default path. On Oracle the same query regresses in A (0.5 → 4.0 ms; only
  82–88 due keys, so there is nothing to stop early).
- **Oldest-created.** `AND completed_at IS NULL` works on Postgres, SQL Server and MongoDB. On MySQL and MariaDB it does not help, but
  `SELECT created_at … ORDER BY created_at LIMIT 1` takes 0.2–0.3 ms. That form returns no row instead of NULL, so the caller must map it.
- **Write cost.** The SQL Server filtered index is about write-neutral. On SQL Server and Oracle, completed_at-leading non-partial indexes make the update to COMPLETED
  noticeably more expensive (SQL Server up to +25%, Oracle up to +90% in one noisy run), because entries move. On MySQL/MariaDB, inserts got cheaper
  (record_key_created dropped) and updates rose +9–13%. On MongoDB writes are neutral.
- **JPA.** The generated SQL has the same shapes as JDBC. `setMaxResults` produces a real `fetch first ? rows only`, but after `GROUP BY`, so it does not stop early.
  All S1 rewrites can be written in JPQL (head-of-key with `order by o.createdAt, o.id` and `setMaxResults`; oldest-created with
  `completedAt is null`); the pages query keeps the IN form. `@Table(indexes)` cannot express `WHERE`/`INCLUDE`, so partial indexes belong in the DDL files
  (production JPA users apply the JDBC DDL with `ddl-auto: validate`). An existing mismatch: the annotation names the index
  `idx_outbox_record_key_completed_created`, while the DDL names it `idx_outbox_record_record_key_completed_created`.
- **MongoDB.** Partial filters accept `{completedAt: null}` and `{status: {$in: […]}}`, and reject `$ne`, `$nin` and `$exists: false`. A query
  uses the partial index only if it contains `completedAt: null` literally. Index changes declared through `@CompoundIndex` do not remove dropped indexes from
  existing deployments.
