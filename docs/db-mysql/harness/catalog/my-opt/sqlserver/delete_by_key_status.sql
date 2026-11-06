-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, my-opt)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
