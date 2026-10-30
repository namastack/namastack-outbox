CREATE INDEX idx_outbox_rec_incomplete ON outbox_record (completed_at, created_at, id, partition_no, status, next_retry_at, record_key);
DROP INDEX idx_outbox_rec_key_cr;
exec dbms_stats.gather_table_stats(user,'OUTBOX_RECORD',cascade=>true,method_opt=>'FOR ALL COLUMNS SIZE AUTO');
