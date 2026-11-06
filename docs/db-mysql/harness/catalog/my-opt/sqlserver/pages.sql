-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, my-opt)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
