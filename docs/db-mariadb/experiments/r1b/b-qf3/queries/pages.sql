-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: MariaDbJdbcOutboxRecordQueries.findIncompleteByKeysAndStatus (query-package, mdb-opt)
-- origin: Q-pgx/pages/pg_setstmt_nomerge
-- class: mariadb-only
SET STATEMENT optimizer_switch='index_merge=off,rowid_filter=off' FOR SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
