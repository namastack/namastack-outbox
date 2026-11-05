-- id: find_incomplete_by_key
-- group: ADMIN
-- kind: read
-- maxrows: none
-- source: JdbcOutboxRecordRepository.findByKeyAndStatusQuery (repository-fields, mdb-main)
SELECT * FROM outbox_record WHERE record_key = :recordKey AND status = :status ORDER BY created_at, id
