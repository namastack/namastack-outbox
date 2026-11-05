-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.deleteByKeyAndStatus (query-package, omin-ref)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
