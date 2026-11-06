-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.findByKeysAndStatusQuery (repository-fields, cmp-main)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status ORDER BY record_key, created_at, id
