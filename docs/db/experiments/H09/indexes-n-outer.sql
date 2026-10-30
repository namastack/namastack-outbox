-- H09 non-partial + ordered outer index: status-leading, so `status = 'NEW'` gives an ordered early-stop scan on
-- every dialect (MySQL/MariaDB/Oracle/SQL Server too); H10 per-key index for the probe.
CREATE INDEX IF NOT EXISTS idx_outbox_record_status_created
    ON outbox_record (status, created_at, id) INCLUDE (partition_no, next_retry_at, record_key);
