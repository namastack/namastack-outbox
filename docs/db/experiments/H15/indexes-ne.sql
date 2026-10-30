-- H15b: same with the negated predicate (also covers any future non-final status).
CREATE INDEX IF NOT EXISTS idx_outbox_record_open_key_created
    ON outbox_record (record_key, created_at, id) WHERE status <> 'COMPLETED';
