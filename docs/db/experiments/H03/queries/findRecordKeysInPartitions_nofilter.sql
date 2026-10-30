-- method: findRecordKeysInPartitions(partitions, NEW, batchSize, ignoreRecordKeysWithPreviousFailure = false)
-- sql: recordKeysQueryWithoutPreviousFailureFilterTemplate
--      H03: outer `o.completed_at IS NULL` added so idx_outbox_record_incomplete_partition (partial) is usable
-- class: hot (every poll when stopOnFirstFailure = false)
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
  AND o.completed_at IS NULL
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
  AND o.completed_at IS NULL
GROUP BY o.record_key
ORDER BY MIN(o.created_at) ASC;
\endif
