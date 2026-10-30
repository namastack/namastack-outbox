-- method: deleteByRecordKeyAndStatus(recordKey, status) (actuator OutboxActuatorEndpoint)
-- sql: deleteByKeyAndStatusQuery
-- class: admin
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
:explain DELETE FROM outbox_record
WHERE record_key = :'sample_key' AND status = :'status';
ROLLBACK;
