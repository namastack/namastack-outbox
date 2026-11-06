-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByIdQuery (repository-fields, my-main)
DELETE FROM outbox_record WHERE id = :id
