-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_strict-10 (from QP-keys_strict-6)
SELECT /*+ JOIN_ORDER(o, e) */ o.record_key FROM outbox_record o LEFT JOIN outbox_record e ON e.record_key = o.record_key AND e.completed_at IS NULL AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id)) WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND e.id IS NULL ORDER BY o.created_at, o.id LIMIT :limit
