-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: none
-- source: overlay forms-b (MIN)
SELECT MIN(next_retry_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND next_retry_at <= :now
