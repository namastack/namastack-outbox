-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-3
-- runner: bind :recordKeysJson as ONE JSON array string, e.g. ["k1","k2"]; :status as usual
SELECT o.* FROM JSON_TABLE(:recordKeysJson, '$[*]' COLUMNS (k VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PATH '$')) k CROSS JOIN LATERAL ( SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = :status AND r.completed_at IS NULL ) o ORDER BY k.k, o.created_at, o.id
