-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, omin-ref)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
