-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-2 (from QI-count_statuses-2)
SELECT t.status, t.cnt FROM (SELECT s.status AS status, (SELECT COUNT(*) FROM outbox_record r WHERE r.status = s.status) AS cnt FROM JSON_TABLE(:statusesJson, '$[*]' COLUMNS (status VARCHAR(20) PATH '$')) AS s) t WHERE t.cnt > 0
