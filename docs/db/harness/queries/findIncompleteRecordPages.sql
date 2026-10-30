-- method: findIncompleteRecordPages(recordKeys)
-- sql: findByKeysAndStatusQuery (status = NEW)
-- class: hot (every poll with keys, OutboxProcessingScheduler.processBatch; <= 1000 keys per call,
--        default batchSize 10 keys)
-- variants: keys=10 keys=100 keys=1000
\ir _params.sql
\if :generic
PREPARE q(:key_types, varchar) AS
SELECT * FROM outbox_record
WHERE record_key IN (:key_ph) AND status = :key_n1
ORDER BY record_key, created_at, id;
:explain EXECUTE q(:keylist, 'NEW');
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key IN (:keylist) AND status = 'NEW'
ORDER BY record_key, created_at, id;
\endif
