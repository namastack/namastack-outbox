-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_nonstrict-2 (from QI-oldest_due_nonstrict-3)
SELECT (SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT 1) AS oldest_due
