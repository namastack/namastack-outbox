-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.countByPartitionsStatus (queries-class, v1)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
