package io.namastack.performance.tooling.jdbc

internal object JdbcPerformanceSql {
    val saveRun =
        """
        INSERT INTO performance_test_run
        (run_id, mode, profile, expected_records, distinct_keys, records_per_key, consumer_instances,
         batch_size, poll_interval, warmup_records, status, producer_target_rate, producer_duration_ms,
         producer_batch_size, producer_workers, measurement_warmup_ms, min_producer_rate_ratio,
         max_backlog_growth_rate, max_end_backlog)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (run_id) DO UPDATE SET
          mode = EXCLUDED.mode, profile = EXCLUDED.profile, expected_records = EXCLUDED.expected_records,
          distinct_keys = EXCLUDED.distinct_keys, records_per_key = EXCLUDED.records_per_key,
          consumer_instances = EXCLUDED.consumer_instances, batch_size = EXCLUDED.batch_size,
          poll_interval = EXCLUDED.poll_interval, warmup_records = EXCLUDED.warmup_records,
          status = EXCLUDED.status, producer_target_rate = EXCLUDED.producer_target_rate,
          producer_duration_ms = EXCLUDED.producer_duration_ms, producer_batch_size = EXCLUDED.producer_batch_size,
          producer_workers = EXCLUDED.producer_workers, measurement_warmup_ms = EXCLUDED.measurement_warmup_ms,
          min_producer_rate_ratio = EXCLUDED.min_producer_rate_ratio,
          max_backlog_growth_rate = EXCLUDED.max_backlog_growth_rate, max_end_backlog = EXCLUDED.max_end_backlog
        """.trimIndent()

    val copySeedRecords =
        """
        COPY performance_seed_record
        (run_id, id, status, record_key, record_type, payload, context, created_at, completed_at,
         failure_count, failure_reason, next_retry_at, partition_no, handler_id)
        FROM STDIN WITH (FORMAT text)
        """.trimIndent()

    val trigger =
        """
        INSERT INTO outbox_record
        (id, status, record_key, record_type, payload, context, created_at, completed_at,
         failure_count, failure_reason, next_retry_at, partition_no, handler_id)
        SELECT id, status, record_key, record_type, payload, context, created_at, completed_at,
               failure_count, failure_reason, next_retry_at, partition_no, handler_id
        FROM performance_seed_record WHERE run_id = ?
        """.trimIndent()

    val insertOutboxRecord =
        """
        INSERT INTO outbox_record
        (id, status, record_key, record_type, payload, context, created_at, completed_at,
         failure_count, failure_reason, next_retry_at, partition_no, handler_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
}
