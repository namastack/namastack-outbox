-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteByStatus (queries-class, v1)
DELETE FROM outbox_record WHERE status = :status
