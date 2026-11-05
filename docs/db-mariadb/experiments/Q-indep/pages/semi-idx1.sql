-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- note: form semi-idx1
SELECT r.* FROM outbox_record r WHERE r.id IN (SELECT i.id FROM outbox_record i WHERE i.status = :status AND i.completed_at IS NULL AND i.record_key IN (:recordKeys)) ORDER BY r.record_key, r.created_at, r.id
