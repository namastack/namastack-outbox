-- method: countByStatus(status) x 3 (NEW, FAILED, COMPLETED) as one statement -- informational only:
--         it needs a new SPI method (countByStatuses) and is a behavioural change, see H30 "open questions".
-- sql: rewrite (not in JdbcOutboxRecordRepository)
-- class: periodic
\ir _params.sql
:explain SELECT status, COUNT(*) FROM outbox_record
GROUP BY status
ORDER BY status;
