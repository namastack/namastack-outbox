-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: MySqlJdbcOutboxRecordQueries.findByStatus (query-package, mdb-opt)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
