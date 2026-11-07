-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findStrictRecordKeys (query-package, mdb-opt)
-- origin: user idea E5: ks-00-base without outer o.completed_at IS NULL; inner probe keeps earlier.completed_at IS NULL (incompleteness test, no status)
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.created_at, o.id LIMIT :limit
