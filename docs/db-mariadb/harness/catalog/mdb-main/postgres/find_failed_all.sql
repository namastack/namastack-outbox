-- id: find_failed_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.findByStatusQuery (repository-fields, mdb-main)
-- note: status FAILED bound by the harness
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
