-- method: deleteFailedRecords(lastRetryBefore, limit), step 1 (step 2 is deleteByIds.sql)
-- sql: findIdsByStatusAndNextRetryBeforeQuery (cutoff = median next_retry_at of FAILED records)
-- class: cleanup (SPI for retention, ADR 0003)
-- maxRows: limit via withMaxRows -> not part of the SQL
\ir _params.sql
:explain SELECT id FROM outbox_record
WHERE status = 'FAILED' AND next_retry_at < :'failed_cutoff'
ORDER BY next_retry_at;
