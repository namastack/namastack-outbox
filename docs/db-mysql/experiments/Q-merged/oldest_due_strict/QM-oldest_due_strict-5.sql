-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_strict-5 (from QI-oldest_due_strict-4, QP-oldest_due_strict-3)
SELECT o.next_retry_at FROM outbox_record o LEFT JOIN outbox_record e ON e.record_key = o.record_key AND e.completed_at IS NULL AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id)) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND e.id IS NULL ORDER BY o.next_retry_at LIMIT 1
