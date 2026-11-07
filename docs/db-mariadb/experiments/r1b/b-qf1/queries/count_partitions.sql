-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- origin: Q-indep/count_partitions/split-null, Q-pgx/count_partitions/cp_split_null
-- class: ok
SELECT ( SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ) + ( SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NOT NULL )
