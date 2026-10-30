-- H09 non-partial (queries-na): status-based incomplete predicate, no partial index
-- method: findOldestDueNextRetryAtByPartitions(partitions, NEW, now, ignoreRecordKeysWithPreviousFailure = true)
-- sql: oldestDueNextRetryAtQueryWithPreviousFailureFilter, H04 rewrite: MIN(...) as a scalar subquery
--      ORDER BY o.next_retry_at LIMIT 1 (early stop on idx_outbox_record_status_retry), H01 bound kept.
--      The scalar subquery returns exactly one row (NULL when nothing matches), like MIN.
-- class: periodic (observability OutboxMetricsCollector, per collection)
-- Worst case (owned partitions without any due record): -v partlist_override=999 (custom mode only).
-- variants: parts=64 parts=256
\ir _params.sql
\if :{?partlist_override}
\if :generic
\else
\set partlist :partlist_override
\endif
\endif
\if :generic
PREPARE q(:part_types, varchar, timestamptz) AS
SELECT (
  SELECT o.next_retry_at
  FROM outbox_record o
  WHERE o.partition_no IN (:part_ph)
    AND o.status = :part_n1
    AND o.next_retry_at <= :part_n2
    AND NOT EXISTS (
      SELECT 1 FROM outbox_record older
      WHERE older.record_key = o.record_key
        AND older.status IN ('NEW', 'FAILED')
        AND older.created_at <= o.created_at
        AND (
          older.created_at < o.created_at
          OR (older.created_at = o.created_at AND older.id < o.id)
        )
    )
  ORDER BY o.next_retry_at ASC
  LIMIT 1
);
:explain EXECUTE q(:partlist, :'status', :'now_ts');
DEALLOCATE q;
\else
:explain SELECT (
  SELECT o.next_retry_at
  FROM outbox_record o
  WHERE o.partition_no IN (:partlist)
    AND o.status = :'status'
    AND o.next_retry_at <= :'now_ts'
    AND NOT EXISTS (
      SELECT 1 FROM outbox_record older
      WHERE older.record_key = o.record_key
        AND older.status IN ('NEW', 'FAILED')
        AND older.created_at <= o.created_at
        AND (
          older.created_at < o.created_at
          OR (older.created_at = o.created_at AND older.id < o.id)
        )
    )
  ORDER BY o.next_retry_at ASC
  LIMIT 1
);
\endif
