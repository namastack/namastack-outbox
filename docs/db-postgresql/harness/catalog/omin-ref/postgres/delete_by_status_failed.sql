-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.deleteByStatus (query-package, omin-ref)
DELETE FROM outbox_record WHERE status = :status
