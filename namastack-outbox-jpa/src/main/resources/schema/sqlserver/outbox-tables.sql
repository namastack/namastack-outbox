IF NOT EXISTS(SELECT *
              FROM INFORMATION_SCHEMA.TABLES
              WHERE TABLE_NAME = 'outbox_record')
CREATE TABLE outbox_record
(
    id            VARCHAR(255) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    aggregate_id  VARCHAR(255) NOT NULL,
    event_type    VARCHAR(255) NOT NULL,
    payload       VARCHAR(MAX) NOT NULL,
    created_at    DATETIME2    NOT NULL,
    completed_at  DATETIME2    NULL,
    retry_count   INT          NOT NULL,
    next_retry_at DATETIME2    NOT NULL,
    partition_no  INT          NOT NULL
        PRIMARY KEY (id),
    INDEX idx_outbox_record_aggregate_created (aggregate_id, created_at),
    INDEX idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at),
    INDEX idx_outbox_record_status_retry (status, next_retry_at),
    INDEX idx_outbox_record_status (status),
    INDEX idx_outbox_record_aggregate_completed_created (aggregate_id, completed_at, created_at)
);

IF NOT EXISTS(SELECT *
              FROM INFORMATION_SCHEMA.TABLES
              WHERE TABLE_NAME = 'outbox_instance')
CREATE TABLE outbox_instance
(
    instance_id    VARCHAR(255) NOT NULL,
    hostname       VARCHAR(255) NOT NULL,
    port           INT          NOT NULL,
    status         VARCHAR(50)  NOT NULL,
    started_at     DATETIME2    NOT NULL,
    last_heartbeat DATETIME2    NOT NULL,
    created_at     DATETIME2    NOT NULL,
    updated_at     DATETIME2    NOT NULL
        PRIMARY KEY (instance_id),
    INDEX idx_outbox_instance_status_heartbeat (status, last_heartbeat),
    INDEX idx_outbox_instance_last_heartbeat (last_heartbeat),
    INDEX idx_outbox_instance_status (status)
);

IF NOT EXISTS(SELECT *
              FROM INFORMATION_SCHEMA.TABLES
              WHERE TABLE_NAME = 'outbox_partition')
CREATE TABLE outbox_partition
(
    partition_number INT       NOT NULL,
    instance_id      VARCHAR(255),
    version          BIGINT    NOT NULL DEFAULT 0,
    assigned_at      DATETIME2 NOT NULL,
    updated_at       DATETIME2 NOT NULL
        PRIMARY KEY (partition_number),
    INDEX idx_outbox_partition_instance_id (instance_id)
);
