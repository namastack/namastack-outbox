-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: OracleJdbcOutboxRecordQueries.findByStatus (query-package, mdb-opt2)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
