-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- note: form pg_force_key (proposer pgx)
SELECT * FROM outbox_record FORCE INDEX (idx_outbox_record_key_completed_created_id) WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
