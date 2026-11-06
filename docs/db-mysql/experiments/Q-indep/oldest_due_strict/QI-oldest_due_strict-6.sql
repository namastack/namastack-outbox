-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QI-oldest_due_strict-6
SELECT /*+ JOIN_ORDER(o, e) NO_BNL(e) INDEX(o idx_outbox_record_status_completed_retry) */ o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id))) ORDER BY o.next_retry_at LIMIT :limit
