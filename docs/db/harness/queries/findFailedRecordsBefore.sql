-- method: findFailedRecords(lastRetryBefore, limit)
-- sql: findByStatusAndNextRetryBeforeQuery (cutoff = median next_retry_at of FAILED records)
-- class: cleanup (SPI for retention, ADR 0003; no caller in the library's main code)
-- maxRows: limit via withMaxRows -> not part of the SQL
\ir _params.sql
:explain SELECT * FROM outbox_record
WHERE status = 'FAILED' AND next_retry_at < :'failed_cutoff'
ORDER BY next_retry_at;
