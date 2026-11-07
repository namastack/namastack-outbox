-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-6 (from QP-count_partitions-2)
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_partition) */ COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
