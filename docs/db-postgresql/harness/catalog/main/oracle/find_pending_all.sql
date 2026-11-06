-- id: find_pending_all
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.findByStatusQuery (repository-fields, main)
SELECT * FROM outbox_record WHERE status = :status ORDER BY created_at, id
