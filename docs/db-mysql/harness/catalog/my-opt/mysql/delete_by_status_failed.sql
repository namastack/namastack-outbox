-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.deleteByStatus (query-package, my-opt)
DELETE FROM outbox_record WHERE status = :status
