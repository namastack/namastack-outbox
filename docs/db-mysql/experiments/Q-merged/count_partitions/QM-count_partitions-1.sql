-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_partitions-1 (from QI-count_partitions-1)
SELECT CAST(COALESCE(SUM(t.c), 0) AS UNSIGNED) FROM (SELECT COUNT(*) AS c FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL UNION ALL SELECT COUNT(*) AS c FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NOT NULL) t
