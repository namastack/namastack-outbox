-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: MariaDbJdbcOutboxRecordQueries.findNonStrictRecordKeys (query-package, mdb-opt)
-- origin: user idea E5, literal reading: kn-00-base without any completed_at IS NULL (inner too; inner has earlier.status = :status). Control: inner probe loses the completed_at equality on (record_key, completed_at, created_at, id) and reads COMPLETED rows of the key
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.status = :status AND earlier.next_retry_at <= :now AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.created_at, o.id LIMIT :limit
