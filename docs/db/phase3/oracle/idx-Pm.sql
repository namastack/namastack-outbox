-- minimal function-based partial index (created_at, id only; other columns by table access)
CREATE INDEX idx_outbox_rec_incomplete_fb ON outbox_record ((CASE WHEN completed_at IS NULL THEN SYS_EXTRACT_UTC(created_at) END), (CASE WHEN completed_at IS NULL THEN id END));
DROP INDEX idx_outbox_rec_key_cr;
exec dbms_stats.gather_table_stats(user,'OUTBOX_RECORD',cascade=>true,method_opt=>'FOR ALL COLUMNS SIZE AUTO');
