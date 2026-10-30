-- method: findIncompleteRecordsByRecordKey(recordKey)
-- sql: findByKeyAndStatusQuery with status inlined as the literal 'NEW' (admin/SPI, no library caller)
\ir _params.sql
\if :generic
PREPARE q(varchar) AS
SELECT * FROM outbox_record
WHERE record_key = $1 AND status = 'NEW'
ORDER BY created_at, id;
:explain EXECUTE q(:'sample_key');
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE record_key = :'sample_key' AND status = 'NEW'
ORDER BY created_at, id;
\endif
