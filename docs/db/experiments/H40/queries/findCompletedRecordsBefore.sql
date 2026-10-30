-- method: findCompletedRecords(completedBefore, limit)
-- sql: findByStatusAndCompletedBeforeQuery (unchanged SQL; H40 changes only indexes). cutoff = percentile :cutpct of completed_at
-- class: cleanup (SPI for retention, ADR 0003; no caller in the library's main code)
-- maxRows: limit via withMaxRows -> not part of the SQL. EXPLAIN ANALYZE runs the plan to completion; for a
--          streaming plan (Index Scan, no Sort) the application stops after `limit` rows: use
--          ../manual/maxrows-completed.sql for that cost.
-- variants: cutpct=0.1 cutpct=0.5 cutpct=0.9 cutpct=1.0
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
:explain SELECT * FROM outbox_record
WHERE status = 'COMPLETED' AND completed_at < :'cut'
ORDER BY completed_at;
