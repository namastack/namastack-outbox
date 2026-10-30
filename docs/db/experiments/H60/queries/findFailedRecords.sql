-- method: findFailedRecords()
-- sql: findByStatusQuery (status = FAILED)
-- class: admin/SPI (no caller in the library's main code)
\ir _params.sql
:explain SELECT * FROM outbox_record
WHERE status = 'FAILED'
ORDER BY created_at, id;
