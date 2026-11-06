-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_due_strict-9 (from QP-oldest_due_strict-5)
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT /*+ NO_SEMIJOIN() */ 1 FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id))) ORDER BY o.next_retry_at LIMIT 1
