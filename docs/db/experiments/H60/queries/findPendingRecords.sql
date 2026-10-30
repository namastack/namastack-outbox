-- method: findPendingRecords()
-- sql: findByStatusQuery (status = NEW)
-- class: admin/SPI (no caller in the library's main code)
\ir _params.sql
:explain SELECT * FROM outbox_record
WHERE status = 'NEW'
ORDER BY created_at, id;
