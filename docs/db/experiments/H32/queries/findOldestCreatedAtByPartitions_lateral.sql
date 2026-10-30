-- method: findOldestCreatedAtByPartitions(partitions, NEW) -- per-partition minimum (informational; LATERAL is not
--         available on MariaDB, SQL Server needs CROSS APPLY, so this is a Postgres-only option, see H32)
-- sql: rewrite: one ordered Limit-1 probe per owned partition on (partition_no, created_at) WHERE completed_at IS NULL
-- class: periodic
-- variants: parts=64 parts=256
\ir _params.sql
:explain SELECT MIN(m.created_at)
FROM unnest(ARRAY[:partlist]) AS p(no)
CROSS JOIN LATERAL (
  SELECT r.created_at FROM outbox_record r
  WHERE r.partition_no = p.no AND r.status = :'status' AND r.completed_at IS NULL
  ORDER BY r.created_at
  LIMIT 1
) m;
