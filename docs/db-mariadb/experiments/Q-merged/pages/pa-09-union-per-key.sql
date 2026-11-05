-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- origin: Q-indep/pages/union-per-key
-- class: code
(SELECT * FROM outbox_record WHERE record_key = :recordKey1 AND status = :status AND completed_at IS NULL) UNION ALL (SELECT * FROM outbox_record WHERE record_key = :recordKey2 AND status = :status AND completed_at IS NULL) ORDER BY record_key, created_at, id
