-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QI-pages-1
SELECT * FROM outbox_record r WHERE r.record_key IN (:recordKeys) AND r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
