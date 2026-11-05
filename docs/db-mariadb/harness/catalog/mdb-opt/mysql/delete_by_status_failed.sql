-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.deleteByStatus (query-package, mdb-opt)
DELETE FROM outbox_record WHERE status = :status
