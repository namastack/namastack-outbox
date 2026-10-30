-- method: deleteByIds(ids); step 2 of deleteCompletedRecords/deleteFailedRecords
-- sql: deleteByIdsQuery (ids = the `limit` (default 1000) oldest COMPLETED ids before the cutoff)
-- class: cleanup
-- kind: write (rolled back)
\ir _params.sql
BEGIN;
:explain DELETE FROM outbox_record
WHERE id IN (:cleanup_ids);
ROLLBACK;
