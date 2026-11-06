-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.findByKeysAndStatus (queries-class, v1)
SELECT o.* FROM ( SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id ) o
