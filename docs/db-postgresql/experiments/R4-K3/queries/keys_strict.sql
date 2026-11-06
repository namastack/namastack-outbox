-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: catalog v1 keys_strict, candidate rows capped (ORDER BY created_at, id LIMIT 1000) before the NOT EXISTS anti-join (round 4 K3, not equivalent by design)
SELECT c.record_key FROM ( SELECT o.record_key, o.created_at, o.id FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.created_at, o.id LIMIT 1000 ) c WHERE NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = c.record_key AND earlier.completed_at IS NULL AND earlier.created_at <= c.created_at AND ( earlier.created_at < c.created_at OR (earlier.created_at = c.created_at AND earlier.id < c.id) ) ) ORDER BY c.created_at, c.id LIMIT :limit
