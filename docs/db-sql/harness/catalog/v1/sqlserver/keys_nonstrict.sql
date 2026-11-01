-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: SqlServerJdbcOutboxRecordQueries.recordKeysWithoutPreviousFailureFilter (queries-class, v1)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC
