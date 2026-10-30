-- method: findIncompleteRecordPages(recordKeys)
-- sql: H14c LATERAL per key, status='NEW' AND completed_at IS NULL (indexes: H12/H02 partial, baseline record_key_completed_created)
-- class: hot (every poll with keys, OutboxProcessingScheduler.processBatch; <= 1000 keys per call)
-- variants: keys=10 keys=100 keys=1000
-- keys become a sorted outer row source; each key is one ordered index range (LATERAL), no global sort of wide rows.
\ir _params.sql
\if :generic
PREPARE q(:key_types) AS
SELECT o.* FROM (SELECT t.k FROM unnest(ARRAY[:key_ph]::varchar[]) AS t(k) ORDER BY t.k) k
CROSS JOIN LATERAL (
  SELECT r.* FROM outbox_record r
  WHERE r.record_key = k.k AND r.status = 'NEW'
    AND r.completed_at IS NULL
  ORDER BY r.created_at, r.id) o
ORDER BY k.k, o.created_at, o.id;
:explain EXECUTE q(:keylist);
DEALLOCATE q;
\else
:explain SELECT o.* FROM (SELECT t.k FROM unnest(ARRAY[:keylist]::varchar[]) AS t(k) ORDER BY t.k) k
CROSS JOIN LATERAL (
  SELECT r.* FROM outbox_record r
  WHERE r.record_key = k.k AND r.status = 'NEW'
    AND r.completed_at IS NULL
  ORDER BY r.created_at, r.id) o
ORDER BY k.k, o.created_at, o.id;
\endif
