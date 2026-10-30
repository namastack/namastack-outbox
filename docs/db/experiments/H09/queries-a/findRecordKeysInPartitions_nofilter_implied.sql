-- H09 (queries-a): see experiments/H09-status-based-incomplete-predicate.md
-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = false)
-- sql: recordKeysQueryWithoutPreviousFailureFilterTemplate, H04 rewrite: "first due record of the key" form
--      without GROUP BY, total order (created_at, id) and a real SQL LIMIT
-- class: hot (every poll when stopOnFirstFailure = false)
-- maxRows: batchSize, now ALSO in the SQL as LIMIT :batch (psql var `batch`, default 10).
--          Equivalence check: --var batch=100000000 (full ordered output vs. the original query).
-- Worst case (owned partitions without any due record): -v partlist_override=999 (custom mode only).
-- variants: parts=64 parts=256
\ir _params.sql
\if :{?batch}
\else
\set batch 10
\endif
\if :{?partlist_override}
\if :generic
\else
\set partlist :partlist_override
\endif
\endif
SELECT '$' || (:parts + 3) AS part_n3 \gset
\if :generic
PREPARE q(:part_types, varchar, timestamptz, bigint) AS
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:part_ph)
  AND o.status = :part_n1
  AND o.next_retry_at <= :part_n2
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record earlier
    WHERE earlier.record_key = o.record_key
      AND earlier.status = :part_n1
      AND earlier.next_retry_at <= :part_n2
      AND earlier.created_at <= o.created_at
      AND (
        earlier.created_at < o.created_at
        OR (earlier.created_at = o.created_at AND earlier.id < o.id)
      )
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :part_n3;
:explain EXECUTE q(:partlist, :'status', :'now_ts', :batch);
DEALLOCATE q;
\else
:explain SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partlist)
  AND o.status = :'status'
  AND o.next_retry_at <= :'now_ts'
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record earlier
    WHERE earlier.record_key = o.record_key
      AND earlier.status = :'status'
      AND earlier.next_retry_at <= :'now_ts'
      AND earlier.created_at <= o.created_at
      AND (
        earlier.created_at < o.created_at
        OR (earlier.created_at = o.created_at AND earlier.id < o.id)
      )
  )
ORDER BY o.created_at ASC, o.id ASC
LIMIT :batch;
\endif
