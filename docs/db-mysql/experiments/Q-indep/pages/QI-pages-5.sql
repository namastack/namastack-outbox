-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QI-pages-5
SELECT p.* FROM JSON_TABLE(:recordKeysJson, '$[*]' COLUMNS (k VARCHAR(255) PATH '$')) AS jt JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE r.record_key = jt.k AND r.status = :status AND r.completed_at IS NULL) AS p ORDER BY p.record_key, p.created_at, p.id
