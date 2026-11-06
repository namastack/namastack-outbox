-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-3 (from QI-count_statuses-3)
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_completed_partition) */ status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
