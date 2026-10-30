-- method: deleteById(id) (OutboxRecordProcessor.completeRecord when delete-completed-records = true)
-- sql: deleteByIdQuery
-- class: hot in delete-completed mode (scenario C), otherwise unused
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
\if :generic
PREPARE q(varchar) AS DELETE FROM outbox_record WHERE id = $1;
:explain EXECUTE q(:'sample_id');
DEALLOCATE q;
\else
:explain DELETE FROM outbox_record
WHERE id = :'sample_id';
\endif
ROLLBACK;
