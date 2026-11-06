-- id: delete_by_ids
-- group: RET
-- kind: delete
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.deleteByIds (query-package, my-opt)
DELETE FROM outbox_record WHERE id IN (:ids)
