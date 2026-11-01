CREATE TABLE IF NOT EXISTS outbox_record
(
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

-- Ordered processing and bulk loading use only the incomplete working set.
CREATE INDEX IF NOT EXISTS idx_outbox_record_key_incomplete_ordered
    ON outbox_record (record_key, created_at, id)
    INCLUDE (partition_no, status, next_retry_at)
    WHERE completed_at IS NULL;

-- Retry selection and pending-record metadata stay outside completed history.
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_retry_incomplete
    ON outbox_record (status, next_retry_at)
    INCLUDE (partition_no, created_at)
    WHERE completed_at IS NULL;

-- Low-cardinality keys allow B-tree deduplication while covering exact status/partition counts.
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_partition
    ON outbox_record (status, partition_no);

-- Key/status lookup supports administrative deletion without indexing per-record timestamps.
CREATE INDEX IF NOT EXISTS idx_outbox_record_key_status
    ON outbox_record (record_key, status);

-- Ordered retention reads stop after their batch rather than scanning/sorting completed history.
CREATE INDEX IF NOT EXISTS idx_outbox_record_completed_retention
    ON outbox_record (completed_at)
    WHERE completed_at IS NOT NULL;

-- Initialization only adds missing objects. Existing indexes are never dropped automatically.

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status_heartbeat
    ON outbox_instance (status, last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_last_heartbeat
    ON outbox_instance (last_heartbeat);

CREATE INDEX IF NOT EXISTS idx_outbox_instance_status
    ON outbox_instance (status);

CREATE INDEX IF NOT EXISTS idx_outbox_partition_instance_id
    ON outbox_partition (instance_id);
