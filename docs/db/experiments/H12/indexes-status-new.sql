-- H12-alt: partial index on the status literal. Matches the ORIGINAL query in custom plans only;
-- unusable in generic plans because the application binds status as a parameter.
CREATE INDEX IF NOT EXISTS idx_outbox_record_new_key_created
    ON outbox_record (record_key, created_at, id) WHERE status = 'NEW';
