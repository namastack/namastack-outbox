-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: P-KK without the inner ORDER BY r.created_at, r.id; OFFSET 0 keeps the LATERAL subquery from being pulled up
SELECT o.* FROM ( SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL OFFSET 0 ) o ORDER BY k.k, o.created_at, o.id
