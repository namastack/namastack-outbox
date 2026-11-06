-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-3 (from QI-count_partitions-3)
SELECT /*+ INDEX(outbox_record idx_outbox_record_status_completed_partition) */ COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
