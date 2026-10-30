-- MANUAL (not for run-explain.sh): cost of the cleanup id select as pgjdbc executes it with withMaxRows(limit).
-- A cursor with cursor_tuple_fraction = 1.0 is planned like the unnamed portal (total cost, no limit known), cursors
-- never use parallel workers (like a maxRows portal), and MOVE stops after :limit rows like the portal suspension.
-- Run: docker exec <c> mkdir -p /harness/m && docker cp harness/queries/_params.sql <c>:/harness/m/ &&
--      docker cp experiments/H40/manual/maxrows-completed.sql <c>:/harness/m/ &&
--      docker exec -i <c> psql -U postgres -X -v cutpct=0.1 -v limit=1000 -f /harness/m/maxrows-completed.sql
-- Reports: the plan (EXPLAIN DECLARE) and "Time:" of 1 warm-up + 3 MOVE runs (take the median of the last 3).
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
EXPLAIN (COSTS ON) DECLARE c NO SCROLL CURSOR FOR
  SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
\timing on
DECLARE c NO SCROLL CURSOR FOR SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
MOVE FORWARD :limit IN c;
CLOSE c;
DECLARE c NO SCROLL CURSOR FOR SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
MOVE FORWARD :limit IN c;
CLOSE c;
DECLARE c NO SCROLL CURSOR FOR SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
MOVE FORWARD :limit IN c;
CLOSE c;
DECLARE c NO SCROLL CURSOR FOR SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'cut' ORDER BY completed_at;
MOVE FORWARD :limit IN c;
CLOSE c;
\timing off
COMMIT;
