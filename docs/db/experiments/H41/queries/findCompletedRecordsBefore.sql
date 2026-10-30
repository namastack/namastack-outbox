-- method: findCompletedRecords(completedBefore, limit)
-- sql: findByStatusAndCompletedBeforeQuery + real SQL limit (`LIMIT :limit` replaces .withMaxRows(limit)). cutoff = percentile :cutpct
-- class: cleanup (SPI for retention, ADR 0003; no caller in the library's main code)
-- note: without withMaxRows pgjdbc runs the portal once, so parallel workers are allowed: the custom mode is the
--       as-executed cost (no serial run needed).
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
\if :generic
PREPARE q(varchar, timestamptz, int) AS
SELECT * FROM outbox_record WHERE status = $1 AND completed_at < $2 ORDER BY completed_at LIMIT $3;
:explain EXECUTE q('COMPLETED', :'cut', :limit);
DEALLOCATE q;
\else
:explain SELECT * FROM outbox_record
WHERE status = 'COMPLETED' AND completed_at < :'cut'
ORDER BY completed_at
LIMIT :limit;
\endif
