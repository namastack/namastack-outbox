-- H30 variant: no status-leading index except status_retry (status, next_retry_at); shows the fallback for countByStatus.
DROP INDEX IF EXISTS idx_outbox_record_status;
