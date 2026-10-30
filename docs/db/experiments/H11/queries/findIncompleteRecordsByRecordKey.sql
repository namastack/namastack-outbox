-- method: findIncompleteRecordsByRecordKey(recordKey)
-- sql: findByKeyAndStatusQuery (status = NEW) -- H11/H12 rewrite: + AND completed_at IS NULL
-- class: admin/SPI (no caller in the library's main code; per-key form of findIncompleteRecordPages)
\ir _params.sql
\if :generic
PREPARE q(varchar, varchar) AS
SELECT * FROM outbox_record
WHERE record_key = $1 AND status = $2 AND completed_at IS NULL
ORDER BY created_at, id;
:explain EXECUTE q(:'sample_key', 'NEW');
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key = :'sample_key' AND status = 'NEW' AND completed_at IS NULL
ORDER BY created_at, id;
\endif
