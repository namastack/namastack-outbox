-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.deleteById (query-package, my-opt)
DELETE FROM outbox_record WHERE id = :id
