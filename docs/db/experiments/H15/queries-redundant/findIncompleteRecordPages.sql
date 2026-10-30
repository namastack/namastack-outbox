-- method: findIncompleteRecordPages(recordKeys)
-- sql: H15 + redundant literal 'status <> COMPLETED' (textual match of the <> partial predicate if PG cannot prove the implication)
-- class: hot (every poll with keys; <= 1000 keys per call)
-- variants: keys=10 keys=100 keys=1000
\ir _params.sql
\if :generic
PREPARE q(:key_types) AS
SELECT * FROM outbox_record
WHERE record_key IN (:key_ph) AND status = 'NEW' AND status <> 'COMPLETED'
ORDER BY record_key, created_at, id;
:explain EXECUTE q(:keylist);
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key IN (:keylist) AND status = 'NEW' AND status <> 'COMPLETED'
ORDER BY record_key, created_at, id;
\endif
