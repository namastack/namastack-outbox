-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-4 (from QI-count_partitions-4)
SELECT CAST(COALESCE(SUM(t.c), 0) AS UNSIGNED) FROM (SELECT /*+ INDEX(outbox_record idx_outbox_record_status_partition) */ COUNT(*) AS c FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL UNION ALL SELECT /*+ INDEX(outbox_record idx_outbox_record_status_partition) */ COUNT(*) AS c FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NOT NULL) t
