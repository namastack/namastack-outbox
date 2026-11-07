-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-final)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
