-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_nonstrict-6 (pre-270c42cf my-opt reference, 2a2663de catalog oldest_due_nonstrict, QI-oldest_due_nonstrict-1, QP-oldest_due_nonstrict-1)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL
