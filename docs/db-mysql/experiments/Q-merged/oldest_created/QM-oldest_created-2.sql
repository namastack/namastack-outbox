-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-oldest_created-2 (from QI-oldest_created-3)
SELECT (SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT 1) AS oldest
