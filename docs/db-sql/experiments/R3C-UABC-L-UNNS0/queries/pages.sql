-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: v1 unnest + LATERAL form without the inner ORDER BY, OFFSET 0 as optimization fence (keeps one probe per key); client sort per key by (created_at, id) with --pages-client-sort (round 3 stage 2, user idea 1)
SELECT o.* FROM ( SELECT t.k FROM unnest(CAST(ARRAY[:recordKeys] AS varchar[])) AS t(k) ORDER BY t.k ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL OFFSET 0 ) o
