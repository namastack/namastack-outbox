-- function-based "partial" covering index: completed rows have all-NULL keys and are not indexed
CREATE INDEX idx_outbox_rec_incomplete_fb ON outbox_record (
 (CASE WHEN completed_at IS NULL THEN SYS_EXTRACT_UTC(created_at) END), (CASE WHEN completed_at IS NULL THEN id END),
 (CASE WHEN completed_at IS NULL THEN partition_no END), (CASE WHEN completed_at IS NULL THEN status END),
 (CASE WHEN completed_at IS NULL THEN SYS_EXTRACT_UTC(next_retry_at) END), (CASE WHEN completed_at IS NULL THEN record_key END));
DROP INDEX idx_outbox_rec_key_cr;
exec dbms_stats.gather_table_stats(user,'OUTBOX_RECORD',cascade=>true,method_opt=>'FOR ALL COLUMNS SIZE AUTO');
