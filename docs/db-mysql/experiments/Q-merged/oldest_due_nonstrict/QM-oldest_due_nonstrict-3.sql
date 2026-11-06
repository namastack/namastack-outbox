-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_nonstrict-3 (from QI-oldest_due_nonstrict-4)
SELECT (SELECT /*+ INDEX(o idx_outbox_record_status_completed_retry) */ o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT 1) AS oldest_due
