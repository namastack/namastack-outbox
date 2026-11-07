-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- origin: Q-indep/pages/json-table
-- class: code
SELECT r.* FROM JSON_TABLE(:recordKeysJson, '$[*]' COLUMNS (k VARCHAR(255) PATH '$')) j JOIN outbox_record r ON r.record_key = j.k WHERE r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
