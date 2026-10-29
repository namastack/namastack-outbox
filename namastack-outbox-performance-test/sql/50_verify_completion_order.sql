-- Run after performance snapshots: the validation scan is outside the measured workload.
-- Validate persisted completion order; this does not prove absence of duplicate external effects.
WITH deliveries AS (
    SELECT completed_at,
           lag(completed_at) OVER (PARTITION BY record_key ORDER BY created_at, id) AS previous_completed_at
    FROM outbox_record
    WHERE starts_with(id, :'run_id' || '-record-')
), checks AS (
    SELECT count(*) AS checked_records,
           count(*) FILTER (WHERE completed_at IS NULL) AS missing_completions,
           count(*) FILTER (WHERE completed_at < previous_completed_at) AS violations
    FROM deliveries
), result AS (
    SELECT *, (SELECT expected_records FROM performance_test_run WHERE run_id = :'run_id') AS expected_records
    FROM checks
)
SELECT json_build_object(
           'checkedRecords', checked_records,
           'expectedRecords', expected_records,
           'missingCompletions', missing_completions,
           'violations', violations,
           'valid', checked_records = expected_records AND missing_completions = 0 AND violations = 0
       ) AS verification,
       checked_records = expected_records AND missing_completions = 0 AND violations = 0 AS verified
FROM result
\gset
\echo :verification
\if :verified
\else
    DO $$ BEGIN RAISE EXCEPTION 'Completion order verification failed'; END $$;
\endif
