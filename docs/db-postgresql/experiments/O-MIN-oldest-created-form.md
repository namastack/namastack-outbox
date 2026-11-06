# `oldest_created`: `ORDER BY` + `LIMIT` vs `MIN` (PostgreSQL)

Driver `harness/bin/omin-pg.sh`, 56 min, 0 failures. Both variants use `feature/db-jdbc-optimization` at `a06a74c0` with its own DDL. `omin-ref` is unchanged (`ORDER BY created_at LIMIT :limit`), `O-MIN` overrides only this query (`experiments/O-MIN/queries/oldest_created.sql`). Two passes, reversed order, container restart. Results `results/{omin-ref,O-MIN}/postgres/<label>-v<1|2>-OMIN/`. Cells: median ms, pass 1 / pass 2. MON threshold from `STATE.md`: 20 % and over 0.15 ms.

Verdict: **`MIN` is the better form, but the gain is small: it clears the threshold in both passes only on S4 and S8-S2, and is never slower above the threshold.** It is the same result as `oldest_due_nonstrict` (`R5-oldest-due.md`). Adoption is the user's decision; the branch is unchanged.

| Cell | `ORDER BY` + `LIMIT` | `MIN` | Change | Result |
|---|---|---|---|---|
| S4 | 0.85 / 0.87 | 0.62 / 0.55 | −27 %, −37 % | win |
| S8-S2 | 0.63 / 0.63 | 0.45 / 0.43 | −30 %, −32 % | win |
| S8-S4 | 0.54 / 0.60 | 0.41 / 0.50 | −24 %, −18 % | no difference, under 0.15 ms |
| S8-S5 | 0.61 / 0.58 | 0.47 / 0.45 | −23 %, −23 % | no difference, under 0.15 ms |
| S5 | 1.21 / 1.15 | 0.60 / 1.01 | −50 %, −12 % | passes disagree |
| S2 | 0.87 / 0.97 | 0.53 / 0.99 | −39 %, +2 % | passes disagree |
| S8-S6 | 0.53 / 0.60 | 0.55 / 0.41 | +4 %, −32 % | passes disagree |
| S8-S7 | 0.53 / 0.64 | 0.37 / 0.60 | −31 %, −7 % | passes disagree |

`MIN` is faster in 14 of 16 pairs. p95 shows no consistent difference.

- **Same execution:** both forms use `idx_outbox_record_status_created_id_incomplete`, with `shared hit=4`, on all 5 labels checked in both passes (`S2`, `S4`, `S5`, `S8-S4`, `S8-S7`, `plans/oldest_created.txt`). No seq scan.
- **Likely cause:** plan cache mode. In all 16 cells `ORDER BY` used custom plans for all 20 timed reps, and `MIN` used generic plans for 16 of 20 (`plancache.csv`). Execution is 0.04–0.08 ms in both captured plans (`plantimes.csv`), so most of the time is outside execution. The cause is inferred; the captured plans are custom plans for both forms, so they do not show the saving directly.
- **Equivalence:** `results/equiv/postgres/{S2,S5,S7}/O-MIN-vs-omin-ref.md`, 26 PASS each, run once (pass 1), no WARN, FAIL or ERROR.
- **Empty result:** by code reading only, not run. `MIN` returns one `NULL` row, which `getInstantOrNull` and `firstOrNull` map to `null`, the same as no rows from `ORDER BY` (`JdbcOutboxRecordRepository.kt:309-317`, `a06a74c0`).

## If adopted

- Override the query in `PostgresJdbcOutboxRecordQueries` only. The query in `DefaultJdbcOutboxRecordQueries` serves other dialects, which were not measured.
- The repository still passes `.param("limit", 1)` and `.withMaxRows(1)`. This run did not use the real repository, so an unused `limit` parameter was not exercised; CI would cover it.

## Limits

- PostgreSQL only, no concurrency run: this query is not in the processing loop.
- The gain is about 0.1–0.3 ms per query on a metrics query, so its practical effect is small.
