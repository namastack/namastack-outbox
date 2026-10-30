-- method: findIncompleteRecordPages(recordKeys)
-- sql: findByKeysAndStatusQuery (status = NEW) -- H13 + H11 combined: record_key = ANY(?) AND completed_at IS NULL
-- class: hot (every poll with keys, OutboxProcessingScheduler.processBatch; <= 1000 keys per call,
--        default batchSize 10 keys)
-- variants: keys=10 keys=100 keys=1000
\ir _params.sql
\if :generic
PREPARE q(varchar[], varchar) AS
SELECT * FROM outbox_record
WHERE record_key = ANY($1) AND status = $2 AND completed_at IS NULL
ORDER BY record_key, created_at, id;
:explain EXECUTE q(ARRAY[:keylist]::varchar[], 'NEW');
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key = ANY(ARRAY[:keylist]::varchar[]) AND status = 'NEW' AND completed_at IS NULL
ORDER BY record_key, created_at, id;
\endif
