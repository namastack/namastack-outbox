-- method: countRecordsByPartitions(partitions, NEW)
-- sql: countByPartitionsStatusQuery
-- class: periodic (observability OutboxMetricsCollector, per collection)
-- variants: parts=64 parts=256
\ir _params.sql
\if :generic
PREPARE q(:part_types, varchar) AS
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:part_ph) AND status = :part_n1;
:explain EXECUTE q(:partlist, :'status');
DEALLOCATE q;
\else
:explain SELECT COUNT(*) FROM outbox_record
WHERE partition_no IN (:partlist) AND status = :'status';
\endif
