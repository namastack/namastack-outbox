-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: E1P base IN form, ORDER BY record_key, created_at, id (R2 E04; completed_at is NULL for all rows)
SELECT * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
