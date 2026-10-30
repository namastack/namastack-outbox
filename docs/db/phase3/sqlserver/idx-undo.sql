IF EXISTS (SELECT 1 FROM sys.indexes WHERE name='idx_outbox_record_incomplete_created') DROP INDEX idx_outbox_record_incomplete_created ON outbox_record;
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name='idx_outbox_record_record_key_created') CREATE INDEX idx_outbox_record_record_key_created ON outbox_record (record_key, created_at);
UPDATE STATISTICS outbox_record WITH FULLSCAN;
