-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordQueries.findByKeysAndStatus (base class, v1) - experiment E1P, PostgreSQL override not used
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id
