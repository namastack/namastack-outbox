-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.deleteByStatus (query-package, mdb-final)
DELETE FROM outbox_record WHERE status = :status
