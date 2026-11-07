-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: H2JdbcOutboxRecordQueries.oldestDueNonStrict (query-package, mdb-opt2)
SELECT next_retry_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND next_retry_at <= :now ORDER BY next_retry_at FETCH FIRST :limit ROWS ONLY
