-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.deleteByStatus (query-package, cmp-jdbc-opt)
DELETE FROM outbox_record WHERE status = :status
