-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: catalog v1 + literal completed_at IS NULL (round 3 stage 2, -L variants)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
