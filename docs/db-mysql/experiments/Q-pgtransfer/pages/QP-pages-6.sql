-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-6
SELECT o.* FROM outbox_record o JOIN ( SELECT DISTINCT d.record_key FROM outbox_record d WHERE d.record_key IN (:recordKeys) ) k ON o.record_key = k.record_key WHERE o.status = :status AND o.completed_at IS NULL ORDER BY o.record_key, o.created_at, o.id
