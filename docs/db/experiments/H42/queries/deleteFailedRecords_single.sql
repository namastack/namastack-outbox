-- method: deleteFailedRecords(lastRetryBefore, limit) as ONE statement (replaces select ids + deleteByIds)
-- sql: rewrite: DELETE ... WHERE id IN (bounded, ordered id select). Postgres syntax; dialect forms in H42.
-- class: cleanup
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
:explain DELETE FROM outbox_record
WHERE id IN (
  SELECT id FROM outbox_record
  WHERE status = 'FAILED' AND next_retry_at < :'failed_cutoff'
  ORDER BY next_retry_at
  LIMIT :limit
);
ROLLBACK;
