-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByIdQuery (repository-fields, mdb-main)
DELETE FROM outbox_record WHERE id = :id
