SELECT json_build_object(
    'capturedAt', clock_timestamp(),
    'verificationSlots', (SELECT json_agg(s) FROM (SELECT slot_name, active, restart_lsn, wal_status
        FROM pg_replication_slots WHERE starts_with(slot_name, 'namastack_perf_order_')) s),
    'wal', (SELECT row_to_json(w) FROM pg_stat_wal w),
    'bgwriter', (SELECT row_to_json(b) FROM pg_stat_bgwriter b),
    'io', (SELECT json_agg(i) FROM pg_stat_io i),
    'checkpointer', (SELECT row_to_json(c) FROM pg_stat_checkpointer c),
    'database', (SELECT row_to_json(d) FROM pg_stat_database d WHERE datname = current_database()),
    'outboxTable', (SELECT row_to_json(t) FROM pg_stat_user_tables t WHERE relname = 'outbox_record'),
    'tableOptions', (SELECT reloptions FROM pg_class WHERE oid = 'outbox_record'::regclass),
    'indexes', (SELECT json_agg(i) FROM (SELECT indexrelname, idx_scan, idx_tup_read, idx_tup_fetch,
                       pg_relation_size(indexrelid) AS size_bytes
                FROM pg_stat_user_indexes WHERE relname = 'outbox_record' ORDER BY indexrelname) i)
);
