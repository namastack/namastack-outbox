-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByIdQuery (repository-fields, cmp-main)
DELETE FROM outbox_record WHERE id = :id
