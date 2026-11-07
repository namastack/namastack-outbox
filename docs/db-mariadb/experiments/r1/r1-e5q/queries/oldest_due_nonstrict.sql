-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestDueNonStrict (query-package, mdb-opt)
-- origin: user idea E5: odn-01-order without completed_at IS NULL (ordered scan on (status, next_retry_at, partition_no))
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now ORDER BY o.next_retry_at LIMIT :limit
