-- method: findOldestCreatedAtByPartitions(partitions, NEW), worst case for variant a: the owned partitions have
--         no NEW rows (here: partition numbers 1000.. that do not exist), so a MIN->Limit scan of (status, created_at)
--         walks every NEW row of the table before returning NULL. Same SQL as the original.
-- sql: oldestCreatedAtByPartitionsStatusQuery
-- class: periodic
-- variants: parts=64 parts=256
\ir _params.sql
SELECT string_agg((1000 + i)::text, ',' ORDER BY i) AS nopartlist FROM generate_series(0, :parts - 1) i \gset
:explain SELECT MIN(created_at) FROM outbox_record
WHERE partition_no IN (:nopartlist) AND status = :'status';
