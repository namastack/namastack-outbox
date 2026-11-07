-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.findByStatus (query-package, mdb-final)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
