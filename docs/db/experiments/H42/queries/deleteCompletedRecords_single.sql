-- method: deleteCompletedRecords(completedBefore, limit) as ONE statement (replaces select ids + deleteByIds)
-- sql: rewrite: DELETE ... WHERE id IN (bounded, ordered id select). Postgres syntax; dialect forms in H42.
-- class: cleanup
-- kind: write (rolled back)
-- baseline to compare: deleteCompletedRecords_selectIds (serial) + deleteByIds
-- variants: cutpct=0.1 cutpct=0.9
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
:explain DELETE FROM outbox_record
WHERE id IN (
  SELECT id FROM outbox_record
  WHERE status = 'COMPLETED' AND completed_at < :'cut'
  ORDER BY completed_at
  LIMIT :limit
);
ROLLBACK;
