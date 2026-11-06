-- id: keys_strict
-- group: HOT
-- kind: read
-- maxrows: batchSize
-- source: QM-keys_strict-4 (from QI-keys_strict-4)
SELECT w.record_key FROM (SELECT i.id, i.record_key, i.created_at, i.status, i.next_retry_at, i.partition_no, ROW_NUMBER() OVER (PARTITION BY i.record_key ORDER BY i.created_at, i.id) AS rn FROM outbox_record i WHERE i.completed_at IS NULL) w WHERE w.rn = 1 AND w.partition_no IN (:partitions) AND w.status = :status AND w.next_retry_at <= :now ORDER BY w.created_at, w.id LIMIT :limit
