-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: catalog base of mdb-opt 2a2663de (branch SQL); same SQL as oc-01-min without setMaxRows
-- class: ok
SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL
