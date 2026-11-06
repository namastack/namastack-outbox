-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_strict-4 (from QI-oldest_due_strict-3)
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND o.id = (SELECT e.id FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL ORDER BY e.created_at, e.id LIMIT 1) ORDER BY o.next_retry_at LIMIT 1
