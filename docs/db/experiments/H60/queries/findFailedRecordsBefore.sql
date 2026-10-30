-- method: findFailedRecords(lastRetryBefore, limit)
-- sql: findByStatusAndNextRetryBeforeQuery + real SQL limit (`LIMIT :limit` replaces .withMaxRows(limit)). cutoff = median next_retry_at of FAILED
-- class: cleanup (SPI for retention, ADR 0003; no caller in the library's main code)
\ir _params.sql
\if :generic
PREPARE q(varchar, timestamptz, int) AS
SELECT * FROM outbox_record WHERE status = $1 AND next_retry_at < $2 ORDER BY next_retry_at LIMIT $3;
:explain EXECUTE q('FAILED', :'failed_cutoff', :limit);
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE status = 'FAILED' AND next_retry_at < :'failed_cutoff'
ORDER BY next_retry_at
LIMIT :limit;
\endif
