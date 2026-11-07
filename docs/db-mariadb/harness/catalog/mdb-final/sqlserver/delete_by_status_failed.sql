-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteByStatus (query-package, mdb-final)
DELETE FROM outbox_record WHERE status = :status
