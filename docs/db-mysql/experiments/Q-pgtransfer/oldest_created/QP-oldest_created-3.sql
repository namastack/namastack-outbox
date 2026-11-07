-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: QP-oldest_created-3
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_created_id) */ MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
