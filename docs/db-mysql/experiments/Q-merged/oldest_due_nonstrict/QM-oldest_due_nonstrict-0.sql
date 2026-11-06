-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_nonstrict-0 (from my-opt catalog oldest_due_nonstrict, QI-oldest_due_nonstrict-1, QP-oldest_due_nonstrict-1)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL
