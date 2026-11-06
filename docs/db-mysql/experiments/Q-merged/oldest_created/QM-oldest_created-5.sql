-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-oldest_created-5 (from QP-oldest_created-4)
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_completed_created_id) */ created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT 1
