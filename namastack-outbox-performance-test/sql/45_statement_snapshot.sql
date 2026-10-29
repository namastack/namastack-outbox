-- Collect outside the measured SQL statements; query text is normalized by PostgreSQL.
SELECT json_build_object(
    'capturedAt', clock_timestamp(),
    'statements', (
        SELECT json_agg(s ORDER BY total_exec_time DESC)
        FROM (
            SELECT queryid::text, query, calls, rows, total_exec_time, mean_exec_time, max_exec_time,
                   shared_blks_hit, shared_blks_read, shared_blks_dirtied, shared_blks_written,
                   temp_blks_read, temp_blks_written, wal_records, wal_fpi, wal_bytes
            FROM pg_stat_statements
            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
              AND query NOT LIKE '%pg_stat_statements%'
            ORDER BY total_exec_time DESC
            LIMIT 200
        ) s
    )
);
