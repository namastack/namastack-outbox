-- method: findCompletedRecords()
-- sql: findByStatusQuery (status = COMPLETED)
-- class: admin/SPI (no caller in the library's main code)
\ir _params.sql
:explain SELECT * FROM outbox_record
WHERE status = 'COMPLETED'
ORDER BY created_at, id;
