-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: QM-oldest_created-0 (from my-opt 270c42cf catalog oldest_created)
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status ORDER BY created_at LIMIT :limit
