-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: user idea E5: oc-00-base (MIN) without completed_at IS NULL
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
