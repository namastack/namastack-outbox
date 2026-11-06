-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-0 (from my-opt catalog count_statuses)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) GROUP BY status
