-- id: count_failed
-- group: MON
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.countByStatusQuery (repository-fields, main)
SELECT COUNT(*) FROM outbox_record WHERE status = :status
