-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = false)
-- sql: recordKeysQueryWithoutPreviousFailureFilterTemplate, H07 rewrite: H06 scalar head subquery plus outer
--      ORDER BY o.completed_at, o.created_at, o.id so the NON-PARTIAL idx_outbox_record_completed_created
--      (completed_at, created_at, id) delivers the order (Postgres does not treat `IS NULL` as a constant for
--      pathkeys, so without the leading completed_at it would sort). All candidates have completed_at NULL.
-- class: hot (every poll when stopOnFirstFailure = false)
-- maxRows: batchSize, ALSO in the SQL as LIMIT :batch (psql var `batch`, default 10).
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
  AND o.completed_at IS NULL
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.completed_at IS NULL
      AND e.status = :part_n1
      AND e.next_retry_at <= :part_n2
    ORDER BY e.completed_at ASC, e.created_at ASC, e.id ASC
    LIMIT 1
  )
ORDER BY o.completed_at ASC, o.created_at ASC, o.id ASC
LIMIT :part_n3;
:explain EXECUTE q(:partlist, :'status', :'now_ts', :batch);
DEALLOCATE q;
\else
:explain SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partlist)
  AND o.status = :'status'
  AND o.next_retry_at <= :'now_ts'
  AND o.completed_at IS NULL
  AND o.id = (
    SELECT e.id FROM outbox_record e
    WHERE e.record_key = o.record_key
      AND e.completed_at IS NULL
      AND e.status = :'status'
      AND e.next_retry_at <= :'now_ts'
    ORDER BY e.completed_at ASC, e.created_at ASC, e.id ASC
    LIMIT 1
  )
ORDER BY o.completed_at ASC, o.created_at ASC, o.id ASC
LIMIT :batch;
\endif
