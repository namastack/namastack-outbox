-- OUTBOX_RECORD
DECLARE
    v_count NUMBER;
BEGIN
    SELECT count(*) INTO v_count FROM user_tables WHERE table_name = 'OUTBOX_RECORD';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE outbox_record
            (
                id             VARCHAR2(255) NOT NULL,
                status         VARCHAR2(20) NOT NULL,
                record_key     VARCHAR2(255) NOT NULL,
                record_type    VARCHAR2(255) NOT NULL,
                payload        NCLOB NOT NULL,
                context        NCLOB,
                created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
                completed_at   TIMESTAMP WITH TIME ZONE,
                failure_count  NUMBER(10) NOT NULL,
                failure_reason VARCHAR2(1000),
                next_retry_at  TIMESTAMP WITH TIME ZONE NOT NULL,
                partition_no   NUMBER(10) NOT NULL,
                handler_id     VARCHAR2(1000) NOT NULL,
                PRIMARY KEY (id)
            )';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_rec_key_cr ON outbox_record (record_key, created_at)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_rec_p_s_r ON outbox_record (partition_no, status, next_retry_at)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_rec_s_r ON outbox_record (status, next_retry_at)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_rec_stat ON outbox_record (status)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_rec_k_c_c ON outbox_record (record_key, completed_at, created_at)';
    END IF;
END;
/

-- OUTBOX_INSTANCE
DECLARE
    v_count NUMBER;
BEGIN
    SELECT count(*) INTO v_count FROM user_tables WHERE table_name = 'OUTBOX_INSTANCE';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE outbox_instance
            (
                instance_id    VARCHAR2(255) PRIMARY KEY,
                hostname       VARCHAR2(255) NOT NULL,
                port           NUMBER(10) NOT NULL,
                status         VARCHAR2(50) NOT NULL,
                started_at     TIMESTAMP WITH TIME ZONE NOT NULL,
                last_heartbeat TIMESTAMP WITH TIME ZONE NOT NULL,
                created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
            )';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_inst_s_h ON outbox_instance (status, last_heartbeat)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_inst_l_h ON outbox_instance (last_heartbeat)';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_inst_stat ON outbox_instance (status)';
    END IF;
END;
/

-- OUTBOX_PARTITION
DECLARE
    v_count NUMBER;
BEGIN
    SELECT count(*) INTO v_count FROM user_tables WHERE table_name = 'OUTBOX_PARTITION';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE outbox_partition
            (
                partition_number NUMBER(10) PRIMARY KEY,
                instance_id      VARCHAR2(255),
                version          NUMBER(19) DEFAULT 0 NOT NULL,
                updated_at       TIMESTAMP WITH TIME ZONE NOT NULL
            )';
        EXECUTE IMMEDIATE 'CREATE INDEX idx_outbox_part_inst_id ON outbox_partition (instance_id)';
    END IF;
END;
/

