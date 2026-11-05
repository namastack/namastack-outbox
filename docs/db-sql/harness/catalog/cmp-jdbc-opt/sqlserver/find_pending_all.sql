-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: SqlServerJdbcOutboxRecordQueries.findByStatus (query-package, cmp-jdbc-opt)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
