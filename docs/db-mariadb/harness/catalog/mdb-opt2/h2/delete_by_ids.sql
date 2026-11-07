-- id: delete_by_ids
-- group: RET
-- kind: delete
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.deleteByIds (query-package, mdb-opt2)
DELETE FROM outbox_record WHERE id IN (:ids)
