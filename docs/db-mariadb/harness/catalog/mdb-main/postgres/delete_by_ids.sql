-- id: delete_by_ids
-- group: RET
-- kind: delete
-- maxrows: none
-- source: JdbcOutboxRecordRepository.deleteByIdsQuery (repository-fields, mdb-main)
DELETE FROM outbox_record WHERE id IN (:ids)
