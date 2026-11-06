-- id: find_completed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: JdbcOutboxRecordRepository.findByStatusAndCompletedBeforeQuery (repository-fields, main)
SELECT * FROM outbox_record WHERE status = :status AND completed_at < :cutoff ORDER BY completed_at
