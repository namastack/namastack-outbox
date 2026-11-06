-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_nonstrict-11 (from QP-keys_nonstrict-7)
SELECT o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL GROUP BY o.record_key ORDER BY MIN(o.created_at), o.record_key LIMIT :limit
