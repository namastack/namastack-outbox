-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QI-pages-3
SELECT /*+ JOIN_ORDER(jt, r) */ r.* FROM JSON_TABLE(:recordKeysJson, '$[*]' COLUMNS (k VARCHAR(255) PATH '$')) AS jt JOIN outbox_record r ON r.record_key = jt.k WHERE r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
