CREATE SCHEMA myschema;

CREATE TABLE IF NOT EXISTS myschema.prefix_outbox_record
(
    id             VARCHAR(255)             NOT NULL,
    status         VARCHAR(20)              NOT NULL,
    record_key     VARCHAR(255)             NOT NULL,
    record_type    VARCHAR(255)             NOT NULL,
    payload        CLOB                     NOT NULL,
    context        CLOB,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at   TIMESTAMP WITH TIME ZONE,
    failure_count  INT                      NOT NULL,
    failure_reason VARCHAR(1000),
    next_retry_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    partition_no   INTEGER                  NOT NULL,
    handler_id     VARCHAR(1000)            NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS myschema.prefix_outbox_instance
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

CREATE TABLE IF NOT EXISTS myschema.prefix_outbox_partition
(
    partition_number INTEGER PRIMARY KEY,
    instance_id      VARCHAR(255),
    version          BIGINT                   NOT NULL DEFAULT 0,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status_completed_created_id
    ON myschema.prefix_outbox_record (status, completed_at, created_at, id, partition_no, next_retry_at, record_key);

CREATE INDEX IF NOT EXISTS idx_outbox_record_key_completed_created_id
    ON myschema.prefix_outbox_record (record_key, completed_at, created_at, id);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status_completed_retry
    ON myschema.prefix_outbox_record (status, completed_at, next_retry_at, partition_no);

CREATE INDEX IF NOT EXISTS idx_outbox_record_status_completed_partition
    ON myschema.prefix_outbox_record (status, completed_at, partition_no);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status_heartbeat
    ON myschema.prefix_outbox_instance (status, last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_last_heartbeat
    ON myschema.prefix_outbox_instance (last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status
    ON myschema.prefix_outbox_instance (status);

CREATE INDEX IF NOT EXISTS idx_outbox_partition_instance_id
    ON myschema.prefix_outbox_partition (instance_id);

CREATE TABLE myschema.prefix_customer
(
    id        VARCHAR(36) PRIMARY KEY,
    firstname VARCHAR(255) NOT NULL,
    lastname  VARCHAR(255) NOT NULL,
    email     VARCHAR(255) NOT NULL
);

