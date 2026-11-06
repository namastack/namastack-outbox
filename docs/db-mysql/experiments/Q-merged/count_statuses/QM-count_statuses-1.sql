-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-1 (from QI-count_statuses-1, QP-count_statuses-4)
/*EACH:statuses: UNION ALL */ SELECT :item AS status, COUNT(*) AS cnt FROM outbox_record WHERE status = :item /*END*/
