-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-5
SELECT /*+ INDEX(outbox_record idx_outbox_record_key_completed_created_id) */ * FROM outbox_record WHERE record_key IN (:recordKeys) AND status = :status AND completed_at IS NULL ORDER BY record_key, created_at, id
