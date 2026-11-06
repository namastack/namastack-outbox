-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QM-pages-10 (from QP-pages-4)
SELECT * FROM (/*EACH:recordKeys: UNION ALL */ SELECT * FROM outbox_record WHERE record_key = :item AND status = :status AND completed_at IS NULL /*END*/) u ORDER BY u.record_key, u.created_at, u.id
