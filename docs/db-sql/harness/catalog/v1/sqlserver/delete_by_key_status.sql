-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteByKeyAndStatus (queries-class, v1)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
