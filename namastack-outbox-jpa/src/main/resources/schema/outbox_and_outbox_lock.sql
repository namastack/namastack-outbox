CREATE TABLE IF NOT EXISTS outbox_record
(
    id            VARCHAR(255)             NOT NULL,
    status        VARCHAR(20)              NOT NULL,
    aggregate_id  VARCHAR(255)             NOT NULL,
    event_type    VARCHAR(255)             NOT NULL,
    payload       TEXT                     NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at  TIMESTAMP WITH TIME ZONE,
    retry_count   INT                      NOT NULL,
    next_retry_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS outbox_lock
(
    aggregate_id VARCHAR(255)             NOT NULL,
    acquired_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    PRIMARY KEY (aggregate_id)
);

-- Performance-critical indices (database-agnostic, compatible with all JPA databases)

-- 1. Aggregate processing order (most important for ordered event processing)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id_created_at ON outbox_record (aggregate_id, created_at);

-- 2. Status-based queries for finding pending/completed/failed records
CREATE INDEX IF NOT EXISTS idx_outbox_status_created_at ON outbox_record (status, created_at);

-- 3. Pending records lookup with retry timing (critical for scheduler performance)
CREATE INDEX IF NOT EXISTS idx_outbox_status_next_retry_at ON outbox_record (status, next_retry_at);

-- 4. Failed records lookup for aggregate filtering (helps exclude failed aggregates)
CREATE INDEX IF NOT EXISTS idx_outbox_status_aggregate_id ON outbox_record (status, aggregate_id);

-- 5. Incomplete records by aggregate (used in processing - covers NULL completed_at queries)
CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_completed_created ON outbox_record (aggregate_id, completed_at, created_at);

-- 6. Lock expiration cleanup and monitoring
CREATE INDEX IF NOT EXISTS idx_outbox_lock_expires_at ON outbox_lock (expires_at);

-- 7. Composite index for scheduler's most frequent query pattern
CREATE INDEX IF NOT EXISTS idx_outbox_scheduler_query ON outbox_record (status, next_retry_at, aggregate_id);

-- 8. Event type queries (useful for monitoring and debugging specific event types)
CREATE INDEX IF NOT EXISTS idx_outbox_event_type_created_at ON outbox_record (event_type, created_at);
