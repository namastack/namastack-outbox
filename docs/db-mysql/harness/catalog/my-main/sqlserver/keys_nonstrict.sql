-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.recordKeysQueryWithoutPreviousFailureFilterTemplate (repository-fields, my-main)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now GROUP BY o.record_key ORDER BY MIN(o.created_at) ASC OFFSET 0 ROWS FETCH NEXT :limit ROWS ONLY
