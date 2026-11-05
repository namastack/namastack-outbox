-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.deleteById (query-package, cmp-jdbc-opt)
DELETE FROM outbox_record WHERE id = :id
