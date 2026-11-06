-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: catalog v1 + literal completed_at IS NULL (round 3 stage 2, -L variants)
SELECT status, COUNT(*) FROM outbox_record WHERE status IN (:statuses) AND completed_at IS NULL GROUP BY status
