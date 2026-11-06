-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: user idea 2a (round 5): ORDER BY + LIMIT instead of MIN, literal completed_at IS NULL
SELECT o.next_retry_at FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL ORDER BY o.next_retry_at LIMIT :limit
