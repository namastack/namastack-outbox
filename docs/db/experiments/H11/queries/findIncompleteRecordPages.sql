-- method: findIncompleteRecordPages(recordKeys)
-- sql: findByKeysAndStatusQuery (status = NEW) -- H11/H12 rewrite: + AND completed_at IS NULL
-- class: hot (every poll with keys, OutboxProcessingScheduler.processBatch; <= 1000 keys per call,
--        default batchSize 10 keys)
-- variants: keys=10 keys=100 keys=1000
\ir _params.sql
\if :generic
PREPARE q(:key_types, varchar) AS
SELECT * FROM outbox_record
WHERE record_key IN (:key_ph) AND status = :key_n1 AND completed_at IS NULL
ORDER BY record_key, created_at, id;
:explain EXECUTE q(:keylist, 'NEW');
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key IN (:keylist) AND status = 'NEW' AND completed_at IS NULL
ORDER BY record_key, created_at, id;
\endif
