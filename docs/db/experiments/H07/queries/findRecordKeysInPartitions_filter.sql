-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = true)
-- sql: recordKeysQueryWithPreviousFailureFilterTemplate, H07 rewrite: H04 head-of-key form (NOT EXISTS probe on the
--      existing idx_outbox_record_record_key_completed_created) with outer ORDER BY o.completed_at, o.created_at, o.id
--      so the NON-PARTIAL idx_outbox_record_completed_created (completed_at, created_at, id) delivers the order
--      (completed_at is NULL for every candidate; Postgres needs the leading key to match the index pathkeys).
-- class: hot (every poll, OutboxProcessingScheduler.loadRecordKeys; stopOnFirstFailure defaults to true)
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
  AND o.completed_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
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
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.completed_at IS NULL
      AND older.created_at <= o.created_at
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
ORDER BY o.completed_at ASC, o.created_at ASC, o.id ASC
LIMIT :batch;
\endif
