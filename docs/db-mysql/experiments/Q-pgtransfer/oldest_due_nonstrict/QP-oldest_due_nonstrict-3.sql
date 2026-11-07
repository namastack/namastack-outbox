-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QP-oldest_due_nonstrict-3
SELECT /*+ INDEX(o idx_outbox_record_status_retry_partition) */ MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL
