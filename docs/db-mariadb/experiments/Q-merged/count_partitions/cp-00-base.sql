-- id: count_partitions
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.countByPartitionsStatus (query-package, mdb-opt)
-- origin: catalog mdb-opt (branch SQL)
-- class: ok
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status
