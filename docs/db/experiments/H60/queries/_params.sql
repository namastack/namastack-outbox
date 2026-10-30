-- Shared parameters for every query file. Included with `\ir _params.sql`.
-- Computed once per psql session (guarded by bench_params_done), so repeated `\i` runs of the
-- same query file in one session reuse the same literals.
--
-- Inputs (psql -v or \set before including):
--   parts    number of owned partitions in the IN list: partitions 0..parts-1 (default 64)
--   keys     number of record keys for findIncompleteRecordPages (default 100). Keys are the ones
--            findRecordKeysInPartitions (no filter, all 256 partitions) would return first:
--            due NEW records ordered by MIN(created_at). Capped by the keys that exist (D has 95).
--   status   status literal for status-parameterised queries (default NEW)
--   limit    cleanup limit, i.e. withMaxRows (default 1000; not part of the SQL, see README)
--   generic  1 = run PREPARE + EXECUTE with plan_cache_mode=force_generic_plan (hot-path files)
--   explain  prefix for the measured statement, e.g. 'EXPLAIN (ANALYZE, BUFFERS)'; default ''
-- Outputs: now_ts, partlist, keylist, keys_n, sample_key, sample_id, sample_created, completed_cutoff,
--          failed_cutoff, cleanup_ids, part_ph/part_types/part_n1/part_n2, key_ph/key_types/key_n1
\if :{?bench_params_done}
\else
\if :{?parts}
\else
\set parts 64
\endif
\if :{?keys}
\else
\set keys 100
\endif
\if :{?status}
\else
\set status NEW
\endif
\if :{?limit}
\else
\set limit 1000
\endif
\if :{?generic}
\else
\set generic 0
\endif
\if :{?explain}
\else
\set explain ''
\endif
-- fixed "now" (see bench-setup.sql)
SELECT to_char(now AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS now_ts FROM bench_now \gset
-- partition IN list and generic-plan placeholders ($1..$parts, then status, now)
SELECT string_agg(i::text, ',' ORDER BY i) AS partlist,
       string_agg('$' || (i + 1), ',' ORDER BY i) AS part_ph,
       string_agg('int', ',') AS part_types,
       '$' || (:parts + 1) AS part_n1,
       '$' || (:parts + 2) AS part_n2
FROM generate_series(0, :parts - 1) i \gset
-- key IN list (what the key query would hand to findIncompleteRecordPages)
SELECT coalesce(string_agg(quote_literal(record_key), ',' ORDER BY m, record_key), '''-none-''') AS keylist,
       count(*) AS keys_n,
       coalesce(string_agg('$' || rn, ',' ORDER BY rn), '$1') AS key_ph,
       coalesce(string_agg('varchar', ','), 'varchar') AS key_types,
       '$' || (greatest(count(*), 1) + 1) AS key_n1
FROM (SELECT record_key, min(created_at) AS m, row_number() OVER (ORDER BY min(created_at), record_key) AS rn
      FROM outbox_record, bench_now
      WHERE status = 'NEW' AND next_retry_at <= bench_now.now
      GROUP BY record_key ORDER BY 2, 1 LIMIT :keys) k \gset
-- a single key / record id: the head NEW record of the first due key
SELECT record_key AS sample_key, id AS sample_id, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') AS sample_created FROM outbox_record
WHERE record_key = split_part(:'keylist', '''', 2) AND status = 'NEW'
ORDER BY created_at, id LIMIT 1 \gset
-- cleanup cutoffs relative to the data (all seeded timestamps lie within ~15h)
SELECT coalesce(to_char((percentile_disc(0.1) WITHIN GROUP (ORDER BY completed_at)) AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '1970-01-01T00:00:00Z') AS completed_cutoff
FROM outbox_record WHERE status = 'COMPLETED' \gset
SELECT coalesce(to_char((percentile_disc(0.5) WITHIN GROUP (ORDER BY next_retry_at)) AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), '1970-01-01T00:00:00Z') AS failed_cutoff
FROM outbox_record WHERE status = 'FAILED' \gset
-- ids deleteCompletedRecords would pass to deleteByIds (first `limit` ids by completed_at)
SELECT coalesce(string_agg(quote_literal(id), ','), '''-none-''') AS cleanup_ids
FROM (SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < :'completed_cutoff'::timestamptz
      ORDER BY completed_at, id LIMIT :limit) c \gset
\if :generic
SET plan_cache_mode = force_generic_plan;
\endif
\set bench_params_done 1
\endif
