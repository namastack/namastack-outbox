-- method: findOldestDueNextRetryAtByPartitions(partitions, NEW, now, ignoreRecordKeysWithPreviousFailure = true)
-- sql: oldestDueNextRetryAtQueryWithPreviousFailureFilter, H01 rewrite kept in H03 (redundant sargable predecessor bound
--      H03: outer `o.completed_at IS NULL` added so idx_outbox_record_incomplete_partition (partial) is usable
--      `older.created_at <= o.created_at`; everything else unchanged)
-- class: periodic (observability OutboxMetricsCollector, per collection)
-- variants: parts=64 parts=256
\ir _params.sql
\if :generic
PREPARE q(:part_types, varchar, timestamptz) AS
SELECT MIN(o.next_retry_at)
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
  );
:explain EXECUTE q(:partlist, :'status', :'now_ts');
DEALLOCATE q;
\else
:explain SELECT MIN(o.next_retry_at)
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
  );
\endif
