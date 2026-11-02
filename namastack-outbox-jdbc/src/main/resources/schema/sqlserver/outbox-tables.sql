IF NOT EXISTS(SELECT *
              FROM INFORMATION_SCHEMA.TABLES
              WHERE TABLE_NAME = 'outbox_record')
CREATE TABLE outbox_record
(
    id             NVARCHAR(255) NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    record_key     VARCHAR(255)  NOT NULL,
    record_type    VARCHAR(255)  NOT NULL,
    payload        NVARCHAR(MAX) NOT NULL,
    context        NVARCHAR(MAX) NULL,
    created_at     DATETIME2(6)  NOT NULL,
    completed_at   DATETIME2(6)  NULL,
    failure_count  INT           NOT NULL,
    failure_reason VARCHAR(1000) NULL,
    next_retry_at  DATETIME2(6)  NOT NULL,
    partition_no   INT           NOT NULL,
    handler_id     VARCHAR(1000) NOT NULL
        PRIMARY KEY (id),
    INDEX idx_outbox_record_record_key_created (record_key, created_at),
    INDEX idx_outbox_record_partition_status_retry (partition_no, status, next_retry_at),
    INDEX idx_outbox_record_status_retry (status, next_retry_at),
    INDEX idx_outbox_record_status (status),
    INDEX idx_outbox_record_record_key_completed_created (record_key, completed_at, created_at)
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
    started_at     DATETIME2(6) NOT NULL,
    last_heartbeat DATETIME2(6) NOT NULL,
    created_at     DATETIME2(6) NOT NULL,
    updated_at     DATETIME2(6) NOT NULL
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
    partition_number INT          NOT NULL,
    instance_id      VARCHAR(255),
    version          BIGINT       NOT NULL DEFAULT 0,
    updated_at       DATETIME2(6) NOT NULL
        PRIMARY KEY (partition_number),
    INDEX idx_outbox_partition_instance_id (instance_id)
);

-- Filtered candidates exclude completed history; completed_at is included so the NULL filter is covered.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('outbox_record')
                 AND name = 'idx_outbox_record_incomplete_partition')
CREATE INDEX idx_outbox_record_incomplete_partition
    ON outbox_record (partition_no, status, next_retry_at)
    INCLUDE (record_key, created_at, completed_at)
    WHERE completed_at IS NULL;

-- Cover the bulk-load filter before looking up payloads for the requested keys.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('outbox_record')
                 AND name = 'idx_outbox_record_key_incomplete_ordered')
CREATE INDEX idx_outbox_record_key_incomplete_ordered
    ON outbox_record (record_key, created_at, id)
    INCLUDE (status, completed_at)
    WHERE completed_at IS NULL;

-- Completed history has a separate access path for ordered, bounded retention reads.
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID('outbox_record')
                 AND name = 'idx_outbox_record_completed_at')
CREATE INDEX idx_outbox_record_completed_at
    ON outbox_record (completed_at)
    INCLUDE (id, status)
    WHERE completed_at IS NOT NULL;
