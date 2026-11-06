-- id: pages
-- group: HOT
-- kind: read
-- maxrows: none
-- source: QP-pages-4
-- runner: expand :recordKeyBranches to N branches 'SELECT * FROM outbox_record WHERE record_key = ? AND status = :status AND completed_at IS NULL' joined with ' UNION ALL ' (status bound once per branch)
SELECT * FROM ( :recordKeyBranches ) u ORDER BY u.record_key, u.created_at, u.id
