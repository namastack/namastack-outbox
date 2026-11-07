-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.countByPartitionStatus (query-package, mdb-opt2)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status
