CREATE TABLE IF NOT EXISTS performance_test_run
(
    run_id                       VARCHAR(255) PRIMARY KEY,
    mode                         VARCHAR(50)              NOT NULL,
    profile                      VARCHAR(100)             NOT NULL,
    expected_records             BIGINT                   NOT NULL,
    distinct_keys                BIGINT                   NOT NULL,
    records_per_key              INTEGER                  NOT NULL,
    consumer_instances           INTEGER                  NOT NULL,
    batch_size                   INTEGER                  NOT NULL,
    poll_interval                VARCHAR(50)              NOT NULL,
    warmup_records               BIGINT                   NOT NULL,
    status                       VARCHAR(50)              NOT NULL,
    seeded_at                    TIMESTAMP WITH TIME ZONE,
    trigger_started_at           TIMESTAMP WITH TIME ZONE,
    started_at                   TIMESTAMP WITH TIME ZONE,
    completed_at                 TIMESTAMP WITH TIME ZONE,
    last_completed_at            TIMESTAMP WITH TIME ZONE,
    completed_records            BIGINT,
    failed_records               BIGINT,
    retry_count                  BIGINT,
    trigger_duration_ms          BIGINT,
    producer_target_rate         BIGINT,
    producer_duration_ms         BIGINT,
    producer_batch_size          INTEGER,
    producer_workers             INTEGER,
    producer_started_at          TIMESTAMP WITH TIME ZONE,
    producer_completed_at        TIMESTAMP WITH TIME ZONE,
    produced_records             BIGINT,
    measurement_warmup_ms        BIGINT,
    min_producer_rate_ratio      DOUBLE PRECISION,
    max_backlog_growth_rate      DOUBLE PRECISION,
    max_end_backlog              BIGINT
);

ALTER TABLE performance_test_run
    ADD COLUMN IF NOT EXISTS mode                        VARCHAR(50) NOT NULL DEFAULT 'backlog-drain',
    ADD COLUMN IF NOT EXISTS producer_target_rate        BIGINT,
    ADD COLUMN IF NOT EXISTS producer_duration_ms        BIGINT,
    ADD COLUMN IF NOT EXISTS producer_batch_size         INTEGER,
    ADD COLUMN IF NOT EXISTS producer_workers            INTEGER,
    ADD COLUMN IF NOT EXISTS producer_started_at         TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS producer_completed_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS produced_records            BIGINT,
    ADD COLUMN IF NOT EXISTS measurement_warmup_ms       BIGINT,
    ADD COLUMN IF NOT EXISTS min_producer_rate_ratio     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS max_backlog_growth_rate     DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS max_end_backlog             BIGINT;

CREATE TABLE IF NOT EXISTS performance_seed_record
(
    run_id         VARCHAR(255)             NOT NULL,
    id             VARCHAR(255)             NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    record_key     VARCHAR(255)             NOT NULL,
    record_type    VARCHAR(255)             NOT NULL,
    payload        TEXT                     NOT NULL,
    context        TEXT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at   TIMESTAMP WITH TIME ZONE,
    failure_count  INT                      NOT NULL,
    failure_reason VARCHAR(1000),
    next_retry_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    partition_no   INTEGER                  NOT NULL,
    handler_id     VARCHAR(1000)            NOT NULL,
    PRIMARY KEY (run_id, id)
);

CREATE INDEX IF NOT EXISTS idx_performance_seed_record_run_id
    ON performance_seed_record (run_id);
