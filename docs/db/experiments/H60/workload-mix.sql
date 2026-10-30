-- H60 variant (rewritten queries). Workload mix for index-usage statistics (section 5.6). Run through workload-mix.sh.
-- MUTATES DATA (marks processed records COMPLETED, cleanup deletes up to `limit` rows per kind):
-- run it last on a container, or on a fresh one.
--
-- polls x (findRecordKeysInPartitions + findIncompleteRecordPages + updateProcessingState per record)
-- every polls/scrapes polls: metrics scrape (countByStatus x 3 statuses, countRecordsByPartitions,
--   findOldestCreatedAtByPartitions, findOldestDueNextRetryAtByPartitions)
-- once at the end: cleanup (deleteCompletedRecords + deleteFailedRecords: select ids, deleteByIds)
-- Required psql vars: completed_cutoff, failed_cutoff (computed by workload-mix.sh before pg_stat_reset,
-- so the percentile scans do not show up in the statistics).
--
-- Statements are run with EXECUTE of literal SQL (custom plans, like the psql query files).
-- To test rewritten queries, copy this file and edit the SQL templates in the DECLARE section
-- (format() placeholders: %s = partition list / key list / id list, %L = literal).
\if :{?parts}
\else
\set parts 64
\endif
\if :{?polls}
\else
\set polls 100
\endif
\if :{?batch}
\else
\set batch 10
\endif
\if :{?scrapes}
\else
\set scrapes 10
\endif
\if :{?filter}
\else
\set filter true
\endif
\if :{?limit}
\else
\set limit 1000
\endif
SELECT set_config('bench.parts', :'parts', false), set_config('bench.polls', :'polls', false),
       set_config('bench.batch', :'batch', false), set_config('bench.scrapes', :'scrapes', false),
       set_config('bench.filter', :'filter', false), set_config('bench.limit', :'limit', false),
       set_config('bench.completed_cutoff', :'completed_cutoff', false),
       set_config('bench.failed_cutoff', :'failed_cutoff', false) \gset

DO $$
DECLARE
  -- SQL templates (JdbcOutboxRecordRepository); %s = IN list, %L = literal
  key_sql_filter text := $q$
    SELECT o.record_key FROM outbox_record o
    WHERE o.partition_no IN (%s) AND o.status = 'NEW' AND o.next_retry_at <= %L AND o.completed_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM outbox_record older
        WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND older.created_at <= o.created_at
          AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))
    ORDER BY o.created_at ASC, o.id ASC LIMIT %s $q$;
  key_sql_nofilter text := $q$
    SELECT o.record_key FROM outbox_record o
    WHERE o.partition_no IN (%s) AND o.status = 'NEW' AND o.next_retry_at <= %L AND o.completed_at IS NULL
      AND NOT EXISTS (SELECT 1 FROM outbox_record earlier
        WHERE earlier.record_key = o.record_key AND earlier.completed_at IS NULL AND earlier.status = 'NEW'
          AND earlier.next_retry_at <= %L AND earlier.created_at <= o.created_at
          AND (earlier.created_at < o.created_at OR (earlier.created_at = o.created_at AND earlier.id < o.id)))
    ORDER BY o.created_at ASC, o.id ASC LIMIT %s $q$;
  pages_sql text := $q$
    SELECT o.* FROM (SELECT t.k FROM unnest(ARRAY[%s]::varchar[]) AS t(k) ORDER BY t.k) k
    CROSS JOIN LATERAL (SELECT r.* FROM outbox_record r WHERE r.record_key = k.k AND r.status = 'NEW'
      AND r.completed_at IS NULL ORDER BY r.completed_at, r.created_at, r.id) o $q$;
  update_state_sql text := $q$
    UPDATE outbox_record SET status = 'COMPLETED', completed_at = %L, failure_count = 0,
      failure_reason = NULL, next_retry_at = %L WHERE id = %L $q$;
  count_status_sql text := $q$ SELECT COUNT(*) FROM outbox_record WHERE status = %L $q$;
  count_parts_sql text := $q$ SELECT COUNT(*) FROM outbox_record WHERE partition_no IN (%s) AND status = 'NEW' $q$;
  oldest_created_sql text := $q$ SELECT MIN(created_at) FROM outbox_record WHERE partition_no IN (%s) AND status = 'NEW' AND completed_at IS NULL $q$;
  oldest_due_filter_sql text := $q$
    SELECT (SELECT o.next_retry_at FROM outbox_record o
    WHERE o.partition_no IN (%s) AND o.status = 'NEW' AND o.next_retry_at <= %L
      AND NOT EXISTS (SELECT 1 FROM outbox_record older
        WHERE older.record_key = o.record_key AND older.completed_at IS NULL AND older.created_at <= o.created_at
          AND (older.created_at < o.created_at OR (older.created_at = o.created_at AND older.id < o.id)))
    ORDER BY o.next_retry_at ASC LIMIT 1) $q$;
  oldest_due_nofilter_sql text := $q$
    SELECT MIN(o.next_retry_at) FROM outbox_record o
    WHERE o.partition_no IN (%s) AND o.status = 'NEW' AND o.next_retry_at <= %L $q$;
  ids_completed_sql text := $q$ SELECT id FROM outbox_record WHERE status = 'COMPLETED' AND completed_at < %L ORDER BY completed_at LIMIT %s $q$;
  ids_failed_sql text := $q$ SELECT id FROM outbox_record WHERE status = 'FAILED' AND next_retry_at < %L ORDER BY next_retry_at LIMIT %s $q$;
  delete_ids_sql text := $q$ DELETE FROM outbox_record WHERE id IN (%s) $q$;

  v_now timestamptz := (SELECT now FROM bench_now);
  v_parts int := current_setting('bench.parts')::int;
  v_polls int := current_setting('bench.polls')::int;
  v_batch int := current_setting('bench.batch')::int;
  v_scrapes int := current_setting('bench.scrapes')::int;
  v_filter boolean := current_setting('bench.filter')::boolean;
  v_limit int := current_setting('bench.limit')::int;
  partlist text := (SELECT string_agg(i::text, ',') FROM generate_series(0, v_parts - 1) i);
  keys text[];
  r record;
  blocked text;
  ids text[];
  cutoff timestamptz;
  n_updates int := 0;
  n_keys int := 0;
  x bigint;
  t timestamptz;
  s text;
BEGIN
  FOR poll IN 1..v_polls LOOP
    -- withMaxRows(batch): the whole query runs, the client keeps the first `batch` keys
    EXECUTE 'SELECT array_agg(record_key) FROM (' ||
            CASE WHEN v_filter THEN format(key_sql_filter, partlist, v_now, v_batch) ELSE format(key_sql_nofilter, partlist, v_now, v_now, v_batch) END || ') k'
      INTO keys;
    keys := keys[1:v_batch];
    IF keys IS NOT NULL AND cardinality(keys) > 0 THEN
      n_keys := n_keys + cardinality(keys);
      blocked := NULL;
      FOR r IN EXECUTE format(pages_sql, (SELECT string_agg(quote_literal(k), ',') FROM unnest(keys) k)) LOOP
        -- strict ordering: stop a key at its first record that is not due
        IF r.record_key IS NOT DISTINCT FROM blocked THEN CONTINUE; END IF;
        IF r.next_retry_at > v_now THEN blocked := r.record_key; CONTINUE; END IF;
        EXECUTE format(update_state_sql, v_now, v_now, r.id);
        n_updates := n_updates + 1;
      END LOOP;
    END IF;
    IF poll % greatest(v_polls / v_scrapes, 1) = 0 THEN
      FOREACH s IN ARRAY ARRAY['NEW', 'FAILED', 'COMPLETED'] LOOP
        EXECUTE format(count_status_sql, s) INTO x;
      END LOOP;
      EXECUTE format(count_parts_sql, partlist) INTO x;
      EXECUTE format(oldest_created_sql, partlist) INTO t;
      EXECUTE format(CASE WHEN v_filter THEN oldest_due_filter_sql ELSE oldest_due_nofilter_sql END, partlist, v_now) INTO t;
    END IF;
    COMMIT;
  END LOOP;
  -- cleanup: deleteCompletedRecords(cutoff = 10th percentile, limit) and deleteFailedRecords(median, limit)
  cutoff := nullif(current_setting('bench.completed_cutoff'), '')::timestamptz;
  IF cutoff IS NOT NULL THEN
    EXECUTE 'SELECT array_agg(id) FROM (' || format(ids_completed_sql, cutoff, v_limit) || ') i' INTO ids;
    ids := ids[1:v_limit];
    IF cardinality(ids) > 0 THEN
      EXECUTE format(delete_ids_sql, (SELECT string_agg(quote_literal(i), ',') FROM unnest(ids) i));
    END IF;
  END IF;
  cutoff := nullif(current_setting('bench.failed_cutoff'), '')::timestamptz;
  IF cutoff IS NOT NULL THEN
    EXECUTE 'SELECT array_agg(id) FROM (' || format(ids_failed_sql, cutoff, v_limit) || ') i' INTO ids;
    ids := ids[1:v_limit];
    IF cardinality(ids) > 0 THEN
      EXECUTE format(delete_ids_sql, (SELECT string_agg(quote_literal(i), ',') FROM unnest(ids) i));
    END IF;
  END IF;
  RAISE NOTICE 'workload mix: polls=% keys=% updates=% (parts=%, batch=%, filter=%)', v_polls, n_keys, n_updates, v_parts, v_batch, v_filter;
END $$;
SELECT pg_stat_force_next_flush();
