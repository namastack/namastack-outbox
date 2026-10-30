-- equivalence reference for H41: the ORIGINAL findIdsByStatusAndCompletedBeforeQuery as the application sees it with withMaxRows(limit):
-- the first :limit rows of the unlimited plan (cursor planned like the unnamed portal, see H40/manual).
-- Use: compare-results.sh <c> experiments/H41/eq/orig_deleteCompletedRecords_selectIds_maxrows.sql experiments/H41/queries/deleteCompletedRecords_selectIds.sql --var cutpct=0.9
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
SET LOCAL cursor_tuple_fraction = 1.0;
DECLARE c NO SCROLL CURSOR FOR
  SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
FETCH FORWARD :limit FROM c;
CLOSE c;
COMMIT;
