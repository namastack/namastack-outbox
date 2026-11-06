-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QM-count_statuses-5 (from QP-count_statuses-2)
/*EACH:statuses: UNION ALL */ SELECT :item AS status, COUNT(*) AS cnt FROM outbox_record WHERE status = :item AND completed_at IS NULL /*END*/
