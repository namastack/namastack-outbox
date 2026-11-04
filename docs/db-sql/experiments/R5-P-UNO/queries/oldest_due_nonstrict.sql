-- id: oldest_due_nonstrict
-- group: MON
-- kind: read
-- maxrows: one
-- source: catalog v1 + outer o.completed_at IS NULL (round 3 U2)
SELECT MIN(o.next_retry_at) FROM outbox_record o WHERE o.partition_no IN (:partitions) AND o.status = :status AND o.next_retry_at <= :now AND o.completed_at IS NULL
