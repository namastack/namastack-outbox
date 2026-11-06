-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_nonstrict-6 (from QI-keys_nonstrict-6)
SELECT /*+ JOIN_ORDER(o, e) NO_BNL(e) */ o.record_key FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS (SELECT 1 FROM outbox_record e WHERE e.record_key = o.record_key AND e.completed_at IS NULL AND e.status = :status AND e.next_retry_at <= :now AND e.created_at <= o.created_at AND (e.created_at < o.created_at OR (e.created_at = o.created_at AND e.id < o.id))) ORDER BY o.created_at, o.id LIMIT :limit
