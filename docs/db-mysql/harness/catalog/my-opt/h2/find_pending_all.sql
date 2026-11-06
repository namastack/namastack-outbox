-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: H2JdbcOutboxRecordQueries.findByStatus (query-package, my-opt)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
