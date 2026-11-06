-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: orchestrator addition (round 5): UABC-L unnest + LATERAL with a top-level ORDER BY, so result order is guaranteed by SQL
SELECT o.* FROM ( SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ORDER BY r.created_at, r.id ) o ORDER BY o.record_key, o.created_at, o.id
