-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- note: form pg_ignore_status (proposer pgx)
SELECT * FROM outbox_record IGNORE INDEX (idx_outbox_record_status_completed_created_id, idx_outbox_record_status_completed_retry, idx_outbox_record_status_completed_partition) WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
