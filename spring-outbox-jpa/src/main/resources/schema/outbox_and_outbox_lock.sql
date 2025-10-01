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

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate_id_created_at ON outbox_record (aggregate_id, created_at);
