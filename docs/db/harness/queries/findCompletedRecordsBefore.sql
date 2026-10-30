-- method: findCompletedRecords(completedBefore, limit)
-- sql: findByStatusAndCompletedBeforeQuery (cutoff = 10th percentile of completed_at)
-- class: cleanup (SPI for retention, ADR 0003; no caller in the library's main code)
-- maxRows: limit via withMaxRows -> not part of the SQL. EXPLAIN ANALYZE runs to completion; with a
--          blocking Sort the real cost is the same, with a streaming plan it would stop after `limit` rows.
\ir _params.sql
:explain SELECT * FROM outbox_record
WHERE status = 'COMPLETED' AND completed_at < :'completed_cutoff'
ORDER BY completed_at;
