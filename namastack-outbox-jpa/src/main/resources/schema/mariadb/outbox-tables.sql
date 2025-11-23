CREATE TABLE IF NOT EXISTS outbox_record
(
    id            VARCHAR(255)           NOT NULL,
    status        VARCHAR(20)            NOT NULL,
    aggregate_id  VARCHAR(255)           NOT NULL,
    event_type    VARCHAR(255)           NOT NULL,
    payload       TEXT                   NOT NULL,
    created_at    TIMESTAMP              NOT NULL,
    completed_at  TIMESTAMP DEFAULT NULL NULL,
    retry_count   INT                    NOT NULL,
    next_retry_at TIMESTAMP              NOT NULL,
    partition_no  INT                    NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_record_aggregate_created (aggregate_id, created_at),
    INDEX idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at),
    INDEX idx_outbox_record_status_retry (status, next_retry_at),
    INDEX idx_outbox_record_status (status),
    INDEX idx_outbox_record_aggregate_completed_created (aggregate_id, completed_at, created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS outbox_instance
(
    instance_id    VARCHAR(255) PRIMARY KEY,
    hostname       VARCHAR(255) NOT NULL,
    port           INT          NOT NULL,
    status         VARCHAR(50)  NOT NULL,
    started_at     TIMESTAMP    NOT NULL,
    last_heartbeat TIMESTAMP    NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL,
    INDEX idx_outbox_instance_status_heartbeat (status, last_heartbeat),
    INDEX idx_outbox_instance_last_heartbeat (last_heartbeat),
    INDEX idx_outbox_instance_status (status)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS outbox_partition
(
    partition_number INT PRIMARY KEY,
    instance_id      VARCHAR(255),
    version          BIGINT    NOT NULL DEFAULT 0,
    assigned_at      TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL,
    INDEX idx_outbox_partition_instance_id (instance_id)
) ENGINE = InnoDB;
