-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.findByKeysAndStatus (queries-class, v1)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, completed_at, created_at, id
