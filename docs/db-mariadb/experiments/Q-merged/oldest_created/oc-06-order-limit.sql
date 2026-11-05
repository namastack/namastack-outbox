-- id: oldest_created
-- group: MON
-- kind: read
-- maxrows: one
-- source: MariaDbJdbcOutboxRecordQueries.oldestCreatedAtByPartitionsStatus (query-package, mdb-opt)
-- origin: catalog base of mdb-opt a06a74c0 (branch before 2a2663de); identical to Q-indep/oldest_created/ord-limit, Q-pgx/oldest_created/oc_order
-- class: ok
SELECT created_at FROM outbox_record WHERE partition_no IN (:partitions) AND status = :status AND completed_at IS NULL ORDER BY created_at LIMIT :limit
