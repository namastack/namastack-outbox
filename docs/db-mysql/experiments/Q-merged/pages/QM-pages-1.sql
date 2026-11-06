-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QM-pages-1 (from QI-pages-2, QP-pages-5)
SELECT /*+ INDEX(r idx_outbox_record_key_completed_created_id) */ * FROM outbox_record r WHERE r.record_key IN (:recordKeys) AND r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
