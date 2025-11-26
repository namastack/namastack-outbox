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
    partition_no  INTEGER                  NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS outbox_instance
(
    instance_id    VARCHAR(255) PRIMARY KEY,
    hostname       VARCHAR(255)             NOT NULL,
    port           INTEGER                  NOT NULL,
    status         VARCHAR(50)              NOT NULL,
    started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_heartbeat TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox_partition
(
    partition_number INTEGER PRIMARY KEY,
    instance_id      VARCHAR(255),
    version          BIGINT                   NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox_partition_lock
(
    id INT PRIMARY KEY
);
INSERT INTO outbox_partition_lock(id) VALUES (1) ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_outbox_record_aggregate_created
    ON outbox_record (aggregate_id, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_partition_status_retry
    ON outbox_record (partition_no, status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status_retry
    ON outbox_record (status, next_retry_at);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status
    ON outbox_record (status);

CREATE INDEX IF NOT EXISTS idx_outbox_record_aggregate_completed_created
    ON outbox_record (aggregate_id, completed_at, created_at);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status_heartbeat
    ON outbox_instance (status, last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_last_heartbeat
    ON outbox_instance (last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status
    ON outbox_instance (status);

CREATE INDEX IF NOT EXISTS idx_outbox_partition_instance_id
    ON outbox_partition (instance_id);
