-- id: find_failed_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: PostgresJdbcOutboxRecordQueries.findByStatus (query-package, cmp-jdbc-opt)
-- note: status FAILED bound by the harness
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
