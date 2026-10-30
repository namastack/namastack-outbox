-- method: findIncompleteRecordPages(recordKeys)
-- sql: findByKeysAndStatusQuery with status inlined as the literal 'NEW' (the repository always binds NEW);
--      needed so a partial index WHERE status IN ('NEW','FAILED') / <> 'COMPLETED' matches in generic plans too
-- class: hot (every poll with keys; <= 1000 keys per call)
-- variants: keys=10 keys=100 keys=1000
\ir _params.sql
\if :generic
PREPARE q(:key_types) AS
SELECT * FROM outbox_record
WHERE record_key IN (:key_ph) AND status = 'NEW'
ORDER BY record_key, created_at, id;
:explain EXECUTE q(:keylist);
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key IN (:keylist) AND status = 'NEW'
ORDER BY record_key, created_at, id;
\endif
