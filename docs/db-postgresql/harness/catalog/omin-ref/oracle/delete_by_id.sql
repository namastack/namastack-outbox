-- id: delete_by_id
-- group: WRITE
-- kind: delete
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.deleteById (query-package, omin-ref)
DELETE FROM outbox_record WHERE id = :id
