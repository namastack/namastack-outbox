-- method: deleteByStatus(status) (actuator OutboxActuatorEndpoint)
-- sql: deleteByStatusQuery
-- class: admin
-- kind: write (rolled back)
-- variants: status=FAILED status=COMPLETED
\ir _params.sql
BEGIN;
:explain DELETE FROM outbox_record
WHERE status = :'status';
ROLLBACK;
