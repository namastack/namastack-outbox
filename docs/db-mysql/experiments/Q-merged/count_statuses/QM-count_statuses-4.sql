-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-4 (from QP-count_statuses-1)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
