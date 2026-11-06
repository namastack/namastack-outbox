-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-0 (from my-opt catalog count_partitions)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
