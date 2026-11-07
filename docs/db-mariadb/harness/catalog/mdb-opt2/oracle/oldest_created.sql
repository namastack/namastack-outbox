-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: OracleJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt2)
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status ORDER BY SYS_EXTRACT_UTC(created_at) FETCH FIRST :limit ROWS ONLY
