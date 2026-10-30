-- H09 non-partial (queries-na): status-based incomplete predicate, no partial index
-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = true)
-- sql: recordKeysQueryWithPreviousFailureFilterTemplate
-- class: hot (every poll, OutboxProcessingScheduler.loadRecordKeys; stopOnFirstFailure defaults to true)
-- maxRows: batchSize (default 10) via JdbcClient.withMaxRows -> not part of the SQL
-- variants: parts=64 parts=256
\ir _params.sql
\if :generic
PREPARE q(:part_types, varchar, timestamptz) AS
SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:part_ph)
  AND o.status = :part_n1
  AND o.next_retry_at <= :part_n2
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.status IN ('NEW', 'FAILED')
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC;
:explain EXECUTE q(:partlist, :'status', :'now_ts');
DEALLOCATE q;
\else
:explain SELECT o.record_key
FROM outbox_record o
WHERE o.partition_no IN (:partlist)
  AND o.status = :'status'
  AND o.next_retry_at <= :'now_ts'
  AND NOT EXISTS (
    SELECT 1 FROM outbox_record older
    WHERE older.record_key = o.record_key
      AND older.status IN ('NEW', 'FAILED')
      AND (
        older.created_at < o.created_at
        OR (older.created_at = o.created_at AND older.id < o.id)
      )
  )
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC;
\endif
