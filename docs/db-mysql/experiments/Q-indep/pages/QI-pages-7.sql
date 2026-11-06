-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QI-pages-7
SELECT /*+ JOIN_ORDER(kt, r) */ r.* FROM (:recordKeysSelects) AS kt JOIN outbox_record r ON r.record_key = kt.k WHERE r.status = :status AND r.completed_at IS NULL ORDER BY r.record_key, r.created_at, r.id
