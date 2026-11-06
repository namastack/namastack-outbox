-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QP-count_statuses-2
-- runner: expand :statusBranches to one branch per status 'SELECT ? AS status, COUNT(*) AS c FROM outbox_record WHERE status = ? AND completed_at IS NULL' joined with ' UNION ALL ' (the status value is bound twice per branch)
:statusBranches
