-- method: countRecordsByPartitions(partitions, NEW)
-- sql: countByPartitionsStatusQuery + redundant `AND completed_at IS NULL` (lets the planner prove the partial-index
--      predicate; relies on the invariant status = 'NEW' => completed_at IS NULL, see H32)
-- class: periodic (observability OutboxMetricsCollector, every 15 s)
-- variants: parts=64 parts=256
\ir _params.sql
\if :generic
PREPARE q(:part_types, varchar) AS
SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (:part_ph) AND status = :part_n1 AND completed_at IS NULL;
:explain EXECUTE q(:partlist, :'status');
DEALLOCATE q;
\else
:explain SELECT COUNT(*) FROM outbox_record
WHERE partition_no IN (:partlist) AND status = :'status' AND completed_at IS NULL;
\endif
