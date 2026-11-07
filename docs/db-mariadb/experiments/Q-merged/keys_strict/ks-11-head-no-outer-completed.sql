-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- origin: user idea (2026-11-06): ks-05-head without the outer o.completed_at IS NULL; subquery keeps e.completed_at IS NULL (incompleteness test, key index seek)
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.id = ( SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL ORDER BY e.created_at, e.id LIMIT 1 ) ORDER BY o.created_at, o.id LIMIT :limit
