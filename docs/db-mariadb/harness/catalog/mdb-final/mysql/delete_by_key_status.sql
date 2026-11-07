-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, mdb-final)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
