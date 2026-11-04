-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: catalog v1 keys_nonstrict, per-partition LATERAL over unnest(:partitions) (round 4 E08); NOT EXISTS text unchanged
SELECT c.record_key FROM unnest(CAST(ARRAY[:partitions] AS int[])) AS p(no) CROSS JOIN LATERAL ( SELECT o.record_key, o.created_at, o.id FROM outbox_record o WHERE o.partition_no = p.no AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL AND NOT EXISTS ( SELECT 1 FROM outbox_record earlier WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.status = :status AND earlier.next_retry_at <= :now AND earlier.created_at <= o.created_at AND ( earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id) ) ) ORDER BY o.created_at, o.id LIMIT :limit ) c ORDER BY c.created_at, c.id LIMIT :limit
