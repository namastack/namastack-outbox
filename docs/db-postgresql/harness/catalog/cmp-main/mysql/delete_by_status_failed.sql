-- id: delete_by_status_failed
-- group: RET
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByStatusQuery (repository-fields, cmp-main)
DELETE FROM outbox_record WHERE status = :status
