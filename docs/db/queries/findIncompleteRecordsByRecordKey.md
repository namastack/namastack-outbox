# Dossier: `findIncompleteRecordsByRecordKey(recordKey)` (group G2, SPI/admin)

SQL (`JdbcOutboxRecordRepository.findByKeyAndStatusQuery`):
```sql
SELECT * FROM outbox_record WHERE record_key = :recordKey AND status = :status   -- 'NEW'
ORDER BY created_at, id
```
Contract: KDoc says "all incomplete records ... sorted by creation time, then record ID"; the implementation
returns only `NEW` (FAILED excluded) — a KDoc/implementation mismatch worth noting in the final report (not a
performance issue). No caller in library main code (SPI, tests); the hot path uses `findIncompleteRecordPages`.
JPA: JPQL `where o.recordKey = :recordKey and o.status = :status order by o.createdAt, o.id`.

Baseline (custom, ms (hits)): A 0.28 (13), B 0.05 (23), C 0.04 (8), D 1.06 (227), E 0.24 (13). Plans: BitmapAnd
`record_key_*` + `status` + Sort (A/D/E), Incremental Sort over `record_key_created` (B/C). Fast everywhere; D reads
the key's 1000-row history.

Hypotheses: same as `findIncompleteRecordPages` — H10 (plain ordered Index Scan, no Sort), H11/H12 (`AND completed_at
IS NULL`; rewritten files in `experiments/H11/queries/` and `H12/queries/`). Expected ≤ 0.1 ms everywhere, D
1.06 → ~0.2 ms. Not a decision driver: accept whatever wins for the bulk query and keep the two queries consistent.

Outcome: pending.
