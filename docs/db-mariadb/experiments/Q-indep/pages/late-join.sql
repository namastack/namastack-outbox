-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- note: form late-join
SELECT r.* FROM outbox_record r JOIN (SELECT id, record_key, created_at FROM outbox_record WHERE record_key IN (:recordKeys) AND completed_at IS NULL) k ON r.id = k.id WHERE r.status = :status ORDER BY k.record_key, k.created_at, k.id
