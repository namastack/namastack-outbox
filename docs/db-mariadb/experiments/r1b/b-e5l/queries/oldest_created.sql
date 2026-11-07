-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: user idea E5: oc-03-order-id without completed_at IS NULL (ordered scan on (status, created_at, id, partition_no, ...), stops at first owned partition)
-- class: status-conditional (code always binds status = NEW; relies on completed_at IS NULL <=> status IN (NEW, FAILED))
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status ORDER BY created_at, id LIMIT :limit
