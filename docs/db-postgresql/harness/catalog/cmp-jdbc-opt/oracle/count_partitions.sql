-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, cmp-jdbc-opt)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
