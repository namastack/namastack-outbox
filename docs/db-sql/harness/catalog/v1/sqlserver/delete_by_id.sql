-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.deleteById (queries-class, v1)
DELETE FROM outbox_record WHERE id = :id
