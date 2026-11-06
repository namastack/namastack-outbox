-- id: count_statuses
-- group: MON
-- kind: read
-- maxrows: none
-- source: QP-count_statuses-4
-- runner: expand :statusBranchesAll to one branch per status 'SELECT ? AS status, COUNT(*) AS c FROM outbox_record WHERE status = ?' joined with ' UNION ALL ' (valid also for COMPLETED; the status value is bound twice per branch)
:statusBranchesAll
