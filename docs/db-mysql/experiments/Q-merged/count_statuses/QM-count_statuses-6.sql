-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-6 (from QP-count_statuses-3)
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_completed_partition) */ status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
