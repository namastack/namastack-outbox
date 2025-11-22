CREATE TABLE IF NOT EXISTS outbox_record
(
    id            VARCHAR2(255)  NOT NULL,
    status        VARCHAR2(20)   NOT NULL,
    aggregate_id  VARCHAR2(255)  NOT NULL,
    event_type    VARCHAR2(255)  NOT NULL,
    payload       VARCHAR2(4000) NOT NULL,
    created_at    TIMESTAMP      NOT NULL,
    completed_at  TIMESTAMP,
    retry_count   INT            NOT NULL,
    next_retry_at TIMESTAMP      NOT NULL,
    partition_no  INTEGER        NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS outbox_instance
(
    instance_id    VARCHAR2(255) PRIMARY KEY,
    hostname       VARCHAR2(255) NOT NULL,
    port           INTEGER       NOT NULL,
    status         VARCHAR2(50)  NOT NULL,
    started_at     TIMESTAMP     NOT NULL,
    last_heartbeat TIMESTAMP     NOT NULL,
    created_at     TIMESTAMP     NOT NULL,
    updated_at     TIMESTAMP     NOT NULL
);

CREATE TABLE IF NOT EXISTS outbox_partition
(
    partition_number INTEGER PRIMARY KEY,
    instance_id      VARCHAR2(255),
    version          INTEGER DEFAULT 0 NOT NULL,
    assigned_at      TIMESTAMP         NOT NULL,
    updated_at       TIMESTAMP         NOT NULL
);

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
