-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: catalog v1 count_statuses (NEW/FAILED form) + literal completed_at IS NULL (R2 A6)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
