-- method: deleteCompletedRecords(completedBefore, limit), step 1 (step 2 is deleteByIds.sql)
-- sql: findIdsByStatusAndCompletedBeforeQuery (cutoff = 10th percentile of completed_at)
-- class: cleanup (SPI for retention, ADR 0003)
-- maxRows: limit via withMaxRows -> not part of the SQL
\ir _params.sql
:explain SELECT id FROM outbox_record
WHERE status = 'COMPLETED' AND completed_at < :'completed_cutoff'
ORDER BY completed_at;
