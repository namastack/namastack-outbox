-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QM-pages-11 (from QP-pages-6)
SELECT r.* FROM outbox_record r JOIN (SELECT DISTINCT d.record_key FROM outbox_record d WHERE d.record_key IN (:recordKeys)) k ON r.record_key = k.record_key WHERE r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
