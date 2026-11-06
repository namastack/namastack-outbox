-- id: find_incomplete_by_key
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.findIncompleteByKeyAndStatus (query-package, omin-ref)
SELECT * FROM outbox_record WHERE record_key = :recordKey AND status = :status AND completed_at IS NULL ORDER BY created_at, id
