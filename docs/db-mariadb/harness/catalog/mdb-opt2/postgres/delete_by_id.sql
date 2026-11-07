-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.deleteById (query-package, mdb-opt2)
DELETE FROM outbox_record WHERE id = :id
