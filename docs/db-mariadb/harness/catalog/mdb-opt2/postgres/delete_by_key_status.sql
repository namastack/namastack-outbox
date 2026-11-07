-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, mdb-opt2)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
