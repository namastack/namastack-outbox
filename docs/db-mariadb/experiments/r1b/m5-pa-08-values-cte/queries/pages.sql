-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- origin: Q-indep/pages/values-cte
-- class: code
WITH k(record_key) AS (VALUES :recordKeysRows) SELECT r.* FROM k JOIN outbox_record r ON r.record_key = k.record_key WHERE r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
