-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- note: form cp_group_sum (proposer pgx)
SELECT CAST(COALESCE(SUM(c), 0) AS SIGNED) FROM ( SELECT partition_no, COUNT(*) AS c FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL GROUP BY partition_no ) t
