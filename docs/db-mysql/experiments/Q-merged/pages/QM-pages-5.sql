-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QM-pages-5 (from QI-pages-6)
/*EACH:recordKeys: UNION ALL */ (SELECT * FROM outbox_record r WHERE r.record_key = :item AND r.status = :status AND r.completed_at IS NULL) /*END*/ ORDER BY record_key, created_at, id
