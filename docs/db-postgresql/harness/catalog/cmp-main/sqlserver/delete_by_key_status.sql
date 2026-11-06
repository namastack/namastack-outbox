-- id: delete_by_key_status
-- group: RET
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByKeyAndStatusQuery (repository-fields, cmp-main)
DELETE FROM outbox_record WHERE record_key = :recordKey AND status = :status
