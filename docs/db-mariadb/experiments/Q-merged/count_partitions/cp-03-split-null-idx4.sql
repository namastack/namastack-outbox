-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/count_partitions/split-null-idx4
-- class: hint
SELECT CAST(SUM(c) AS SIGNED) FROM (SELECT COUNT(*) AS c FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL UNION ALL SELECT COUNT(*) AS c FROM outbox_record USE INDEX (idx_outbox_record_status_completed_partition) WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NOT NULL) t
