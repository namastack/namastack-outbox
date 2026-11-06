-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QI-count_statuses-1
/*EACH:statuses: UNION ALL */ SELECT :item AS status, COUNT(*) AS cnt FROM outbox_record WHERE status = :item /*END*/
