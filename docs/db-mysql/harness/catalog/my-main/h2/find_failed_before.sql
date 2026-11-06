-- id: find_failed_before
-- group: RET
-- kind: read
-- maxrows: retention
-- source: JdbcOutboxRecordRepository.findByStatusAndNextRetryBeforeQuery (repository-fields, my-main)
SELECT * FROM outbox_record WHERE status = :status AND next_retry_at < :cutoff ORDER BY next_retry_at
