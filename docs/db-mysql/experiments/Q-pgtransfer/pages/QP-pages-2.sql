-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-2
-- runner: expand :recordKeysRows to ROW(?),ROW(?),... (one ROW per key)
SELECT o.* FROM ( SELECT t.k FROM (VALUES :recordKeysRows) AS t(k) ) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ) o ORDER BY k.k, o.created_at, o.id
