-- id: count_partition_single
-- group: MON
-- kind: read
-- maxrows: none
-- source: catalog v1 + literal completed_at IS NULL (R2 A6)
SELECT COUNT(*) FROM outbox_record WHERE partition_no = :partitionNo AND status = :status AND completed_at IS NULL
