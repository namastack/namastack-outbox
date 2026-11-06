-- id: keys_nonstrict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QI-keys_nonstrict-4
SELECT w.record_key FROM (SELECT i.id, i.record_key, i.created_at, i.partition_no, ROW_NUMBER() OVER (PARTITION BY i.record_key ORDER BY i.created_at, i.id) AS rn FROM outbox_record i WHERE i.status = :status AND i.completed_at IS NULL AND i.next_retry_at <= :now) w WHERE w.rn = 1 AND w.partition_no IN (:partitions) ORDER BY w.created_at, w.id LIMIT :limit
