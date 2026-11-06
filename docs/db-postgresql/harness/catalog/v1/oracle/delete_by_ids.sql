-- id: delete_by_ids
-- group: RET
-- kind: delete
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.deleteByIds (queries-class, v1)
DELETE FROM outbox_record WHERE id IN (:ids)
