-- id: delete_by_ids
-- group: RET
-- kind: delete
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.deleteByIds (query-package, mdb-final)
DELETE FROM outbox_record WHERE id IN (:ids)
