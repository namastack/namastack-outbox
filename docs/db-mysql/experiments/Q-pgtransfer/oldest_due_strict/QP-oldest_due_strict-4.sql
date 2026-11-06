-- id: oldest_due_strict
-- group: MON
-- kind: read
-- maxrows: one
-- source: QP-oldest_due_strict-4
SELECT MIN(o.next_retry_at) FROM outbox_record o LEFT JOIN outbox_record earlier ON earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND earlier.id IS NULL
