-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-1
SELECT o.* FROM ( SELECT DISTINCT d.record_key AS k FROM outbox_record d WHERE d.record_key IN (:recordKeys) ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ) o ORDER BY k.k, o.created_at, o.id
