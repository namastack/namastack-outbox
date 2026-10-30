-- equivalence for H42: ids deleted by the single statement (rolled back). Compare with
-- H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql; "FAIL (same rows, different order)" counts as PASS here
-- because a DELETE is set-based (RETURNING order is unspecified).
\ir _params.sql
\if :{?cutpct}
\else
\set cutpct 0.1
\endif
\if :{?cut_for}
\else
\set cut_for none
\endif
SELECT (:'cut_for' = :'cutpct') AS cut_cached \gset
\if :cut_cached
\else
SELECT coalesce(to_char((percentile_disc(:cutpct) WITHIN GROUP (ORDER BY completed_at)) AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '1970-01-01T00:00:00Z') AS cut, :'cutpct' AS cut_for
FROM outbox_record WHERE status = 'COMPLETED' \gset
\endif
BEGIN;
WITH d AS (
  DELETE FROM outbox_record
  WHERE id IN (
    SELECT id FROM outbox_record
    WHERE status = 'COMPLETED' AND completed_at < :'cut'
    ORDER BY completed_at
    LIMIT :limit
  )
  RETURNING id
)
SELECT id FROM d;
ROLLBACK;
