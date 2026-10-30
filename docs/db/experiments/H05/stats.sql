-- H05 variant X (diagnostic): multivariate MCV statistics so the planner knows status = 'NEW' implies
-- completed_at IS NULL (independence assumption gives 2% x 2% = 0.04% -> 343 rows in D, actual 19,000).
-- Apply on top of the H04/H05 indexes, run the UNCHANGED H04 query files.
CREATE STATISTICS IF NOT EXISTS outbox_record_status_completed_stx (mcv)
    ON status, completed_at FROM outbox_record;
ANALYZE outbox_record;
