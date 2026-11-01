SELECT json_build_object(
    'capturedAt', clock_timestamp(),
    'verificationSlots', (SELECT json_agg(s) FROM (SELECT slot_name, active, restart_lsn, wal_status
        FROM pg_replication_slots WHERE starts_with(slot_name, 'namastack_perf_order_')) s),
    'partitionOwners', (SELECT json_agg(p) FROM (SELECT instance_id, count(*) AS partitions FROM outbox_partition GROUP BY instance_id ORDER BY instance_id) p),
    'wal', (SELECT row_to_json(w) FROM pg_stat_wal w),
    'bgwriter', (SELECT row_to_json(b) FROM pg_stat_bgwriter b),
    'io', (SELECT json_agg(i) FROM pg_stat_io i),
    'checkpointer', (SELECT row_to_json(c) FROM pg_stat_checkpointer c),
    'database', (SELECT row_to_json(d) FROM pg_stat_database d WHERE datname = current_database()),
    'vacuumProgress', (SELECT json_agg(v) FROM pg_stat_progress_vacuum v WHERE relid='outbox_record'::regclass),
    'outboxTable', (SELECT row_to_json(t) FROM pg_stat_user_tables t WHERE relname = 'outbox_record'),
    'statisticsObjects', (SELECT json_agg(s) FROM (SELECT stxname, stxkeys, stxkind FROM pg_statistic_ext WHERE stxrelid = 'outbox_record'::regclass) s),
    'columnStatistics', (SELECT json_agg(s) FROM (SELECT attname, null_frac, n_distinct, most_common_vals::text, most_common_freqs FROM pg_stats WHERE tablename='outbox_record' AND schemaname='public' AND attname IN ('status','completed_at','record_key','created_at','next_retry_at')) s),
    'tableSizes', json_build_object('heap',pg_relation_size('outbox_record'),'table',pg_table_size('outbox_record'),'indexes',pg_indexes_size('outbox_record'),'total',pg_total_relation_size('outbox_record')),
    'tableOptions', (SELECT reloptions FROM pg_class WHERE oid = 'outbox_record'::regclass),
    'indexes', (SELECT json_agg(i) FROM (SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch,
                       pg_relation_size(indexrelid) AS size_bytes
                FROM pg_stat_user_indexes WHERE relname = 'outbox_record' ORDER BY indexrelname) i)
);
