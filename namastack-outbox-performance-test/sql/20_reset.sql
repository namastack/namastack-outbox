TRUNCATE TABLE outbox_record;
TRUNCATE TABLE outbox_partition;
TRUNCATE TABLE outbox_instance;
TRUNCATE TABLE performance_seed_record;
TRUNCATE TABLE performance_test_run;

-- Apply the index-only profile to persistent benchmark volumes as well as fresh schemas.
DROP INDEX IF EXISTS idx_outbox_record_status;
DROP INDEX IF EXISTS idx_outbox_record_record_key_completed_created;

-- Restore the control profile before applying any opt-in maintenance settings.
ALTER TABLE outbox_record RESET (
    autovacuum_vacuum_scale_factor, autovacuum_vacuum_threshold,
    autovacuum_analyze_scale_factor, autovacuum_analyze_threshold,
    autovacuum_vacuum_cost_limit, autovacuum_vacuum_cost_delay
);


-- Restore the measured five-index control before optional experiments.
DROP INDEX IF EXISTS idx_outbox_record_ready_order;
DROP INDEX IF EXISTS idx_outbox_record_key_status_created;
