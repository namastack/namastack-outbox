-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, my-opt)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
