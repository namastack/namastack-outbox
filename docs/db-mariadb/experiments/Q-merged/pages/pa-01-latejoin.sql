-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- origin: Q-indep/pages/late-join
-- class: ok
SELECT r.* FROM outbox_record r JOIN (SELECT id, record_key, created_at FROM outbox_record WHERE record_key IN (:recordKeys) AND completed_at IS NULL) k ON r.id = k.id WHERE r.status = :status ORDER BY k.record_key, k.created_at, k.id
