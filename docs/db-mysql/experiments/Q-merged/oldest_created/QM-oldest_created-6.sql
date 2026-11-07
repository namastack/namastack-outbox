-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-oldest_created-6 (pre-270c42cf my-opt reference, 2a2663de catalog oldest_created, QI-oldest_created-1, QP-oldest_created-1)
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
