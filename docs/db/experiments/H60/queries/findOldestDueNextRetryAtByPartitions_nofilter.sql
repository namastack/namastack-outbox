-- method: findOldestDueNextRetryAtByPartitions(partitions, NEW, now, ignoreRecordKeysWithPreviousFailure = false)
-- sql: oldestDueNextRetryAtQueryWithoutPreviousFailureFilter
-- class: periodic (observability OutboxMetricsCollector, per collection)
-- variants: parts=64 parts=256
\ir _params.sql
\if :generic
PREPARE q(:part_types, varchar, timestamptz) AS
SELECT MIN(o.next_retry_at)
FROM outbox_record o
WHERE o.partition_no IN (:part_ph)
  AND o.status = :part_n1
  AND o.next_retry_at <= :part_n2;
:explain EXECUTE q(:partlist, :'status', :'now_ts');
DEALLOCATE q;
\else
:explain SELECT MIN(o.next_retry_at)
FROM outbox_record o
WHERE o.partition_no IN (:partlist)
  AND o.status = :'status'
  AND o.next_retry_at <= :'now_ts';
\endif
