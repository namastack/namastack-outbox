-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-5 (from QP-count_partitions-1)
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
