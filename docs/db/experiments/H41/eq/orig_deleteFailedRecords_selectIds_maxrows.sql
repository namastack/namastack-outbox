-- equivalence reference for H41: the ORIGINAL findIdsByStatusAndNextRetryBeforeQuery with withMaxRows(limit) semantics (first :limit rows).
\ir _params.sql
BEGIN;
SET LOCAL cursor_tuple_fraction = 1.0;
DECLARE c NO SCROLL CURSOR FOR
  SELECT id FROM outbox_record WHERE status = 'FAILED' AND next_retry_at < :'failed_cutoff' ORDER BY next_retry_at;
FETCH FORWARD :limit FROM c;
CLOSE c;
COMMIT;
